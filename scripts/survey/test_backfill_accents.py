"""Integration tests for backfill_accents; requires Docker (Testcontainers)."""

from __future__ import annotations

import csv
import sys
import uuid as _uuid
from pathlib import Path

import psycopg
import pytest
from testcontainers.postgres import PostgresContainer

sys.path.insert(0, str(Path(__file__).parent))

import backfill_accents as ba  # noqa: E402

_REPO_ROOT = Path(__file__).resolve().parents[2]
_MIGRATIONS = _REPO_ROOT / "survey/infrastructure/src/main/resources/db/migration"


def _docker_available() -> bool:
    try:
        import docker  # type: ignore[import-untyped]

        docker.from_env().ping()
    except Exception:
        return False
    return True


@pytest.fixture(scope="module")
def pg():
    # Skip only the DB-backed tests; the load_pos_agnostic unit tests need no container.
    if not _docker_available():
        pytest.skip("Docker daemon not available")
    with PostgresContainer("postgres:16-alpine") as p:
        with psycopg.connect(p.get_connection_url(driver=None), autocommit=True) as c:
            with c.cursor() as cur:
                cur.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto")
                for migration in sorted(_MIGRATIONS.glob("V*.sql")):
                    cur.execute(migration.read_text())
        yield p


@pytest.fixture
def conn(pg):
    with psycopg.connect(pg.get_connection_url(driver=None), autocommit=False) as c:
        yield c
        # Roll back any aborted txn left by a failing test so the TRUNCATE can run.
        c.rollback()
        with c.cursor() as cur:
            cur.execute("TRUNCATE campaigns, ratings, pair_ratings, survey_items CASCADE")
        c.commit()


def _dbnary(tmp_path: Path, rows: list[dict[str, str]]) -> Path:
    path = tmp_path / "dbnary_fr.csv"
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["lemma", "pos", "definition"])
        w.writeheader()
        w.writerows(rows)
    return path


def _frequencies(tmp_path: Path, freqs: dict[str, int]) -> dict[str, int]:
    path = tmp_path / "words-fr.csv"
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["word", "frequency"])
        w.writeheader()
        w.writerows({"word": word, "frequency": freq} for word, freq in freqs.items())
    return ba.load_frequencies(path)


def _insert_item(conn, mot: str, pos: str = "adjectif") -> _uuid.UUID:
    # tier omitted: 'mid' default; V1 CHECK requires lowercase ('high','mid','low','excluded').
    item_id = _uuid.uuid4()
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO survey_items
              (item_id, mot, definition, pos, categorie, style,
               force_claimed, longueur, source, source_batch)
            VALUES (%s, %s, 'def', %s, 'autre', 'definition_directe',
                    1, length(%s), 'synthetic_v1', 'test')
            """,
            (item_id, mot, pos, mot),
        )
    conn.commit()
    return item_id


def _fetch_mot(conn, item_id: _uuid.UUID) -> str:
    with conn.cursor() as cur:
        cur.execute("SELECT mot FROM survey_items WHERE item_id = %s", (item_id,))
        return cur.fetchone()[0]


def test_backfill_recovers_accents_from_dbnary(conn, tmp_path):
    """An ASCII `SOLDE adjectif` row gets rewritten to `SOLDÉ`."""
    item_id = _insert_item(conn, "SOLDE", pos="adjectif")
    dbnary = _dbnary(
        tmp_path,
        [{"lemma": "soldé", "pos": "adjective", "definition": "Vendu au rabais."}],
    )
    lookup = ba.load_lookup(dbnary)

    summary = ba.backfill(conn, lookup)
    assert summary == {"updated": 1, "skipped": 0, "already": 0}
    assert _fetch_mot(conn, item_id) == "SOLDÉ"


def test_backfill_keeps_accent_via_grammalecte_tiebreak(conn, tmp_path):
    """`RIVIERE` with both DBnary twins live resolves to `RIVIÈRE` on grammalecte frequency, not stripped."""
    item_id = _insert_item(conn, "RIVIERE", pos="nom_commun")
    dbnary = _dbnary(
        tmp_path,
        [
            {"lemma": "riviere", "pos": "noun", "definition": "Orthographe ancienne de rivière."},
            {"lemma": "rivière", "pos": "noun", "definition": "Cours d'eau."},
        ],
    )
    frequencies = _frequencies(tmp_path, {"rivière": 7_800_000})
    lookup = ba.load_lookup(dbnary, frequencies)

    summary = ba.backfill(conn, lookup)
    assert summary == {"updated": 1, "skipped": 0, "already": 0}
    assert _fetch_mot(conn, item_id) == "RIVIÈRE"


def test_backfill_drops_desuet_only_variant(conn, tmp_path):
    """A désuet-only twin is dropped so the live accented variant wins even with no frequency data."""
    item_id = _insert_item(conn, "MERE", pos="nom_commun")
    dbnary = _dbnary(
        tmp_path,
        [
            {"lemma": "mere", "pos": "noun", "definition": "(Vieilli) Forme ancienne."},
            {"lemma": "mère", "pos": "noun", "definition": "Femme qui a enfanté."},
        ],
    )
    lookup = ba.load_lookup(dbnary, _frequencies(tmp_path, {}))

    summary = ba.backfill(conn, lookup)
    assert summary == {"updated": 1, "skipped": 0, "already": 0}
    assert _fetch_mot(conn, item_id) == "MÈRE"


def test_backfill_skips_when_no_dbnary_match(conn, tmp_path, capsys):
    """A row whose mot+pos has no DBnary entry is left unchanged and logged."""
    item_id = _insert_item(conn, "XYZQZW", pos="nom_commun")
    dbnary = _dbnary(tmp_path, [])
    lookup = ba.load_lookup(dbnary)

    summary = ba.backfill(conn, lookup)
    assert summary == {"updated": 0, "skipped": 1, "already": 0}
    assert _fetch_mot(conn, item_id) == "XYZQZW"
    err = capsys.readouterr().err
    assert "no DBnary match" in err
    assert "XYZQZW" in err


def test_backfill_is_idempotent(conn, tmp_path):
    """Running twice on a recovered row counts as 'already' the second time."""
    item_id = _insert_item(conn, "SOLDE", pos="adjectif")
    dbnary = _dbnary(
        tmp_path,
        [{"lemma": "soldé", "pos": "adjective", "definition": "Vendu au rabais."}],
    )
    lookup = ba.load_lookup(dbnary)

    first = ba.backfill(conn, lookup)
    assert first == {"updated": 1, "skipped": 0, "already": 0}
    assert _fetch_mot(conn, item_id) == "SOLDÉ"

    second = ba.backfill(conn, lookup)
    assert second == {"updated": 0, "skipped": 0, "already": 1}
    assert _fetch_mot(conn, item_id) == "SOLDÉ"


def test_pos_agnostic_includes_single_surface(tmp_path):
    """A stripped form with one DBnary surface across all POS is recoverable POS-blind."""
    dbnary = _dbnary(
        tmp_path,
        [
            {"lemma": "élégant", "pos": "adjective", "definition": "Qui a de l'élégance."},
            {"lemma": "élégant", "pos": "noun", "definition": "Homme élégant."},
        ],
    )
    assert ba.load_pos_agnostic(dbnary) == {"elegant": "élégant"}


def test_pos_agnostic_excludes_homograph(tmp_path):
    """naufrage (noun) and naufragé (adj) are distinct surfaces, so neither is offered POS-blind."""
    dbnary = _dbnary(
        tmp_path,
        [
            {"lemma": "naufrage", "pos": "noun", "definition": "Perte d'un navire."},
            {"lemma": "naufragé", "pos": "adjective", "definition": "Qui a fait naufrage."},
        ],
    )
    assert "naufrage" not in ba.load_pos_agnostic(dbnary)


def test_backfill_accents_autre_via_pos_agnostic(conn, tmp_path):
    """A legacy `ELEGANT autre` row gets `ÉLÉGANT` from the POS-agnostic fallback."""
    item_id = _insert_item(conn, "ELEGANT", pos="autre")
    dbnary = _dbnary(
        tmp_path,
        [
            {"lemma": "élégant", "pos": "adjective", "definition": "Qui a de l'élégance."},
            {"lemma": "élégant", "pos": "noun", "definition": "Homme élégant."},
        ],
    )
    summary = ba.backfill(conn, ba.load_lookup(dbnary), ba.load_pos_agnostic(dbnary))
    assert summary == {"updated": 1, "skipped": 0, "already": 0}
    assert _fetch_mot(conn, item_id) == "ÉLÉGANT"


def test_backfill_skips_ambiguous_autre_homograph(conn, tmp_path):
    """A `NAUFRAGE autre` row is left ASCII when DBnary holds a noun/adj homograph pair."""
    item_id = _insert_item(conn, "NAUFRAGE", pos="autre")
    dbnary = _dbnary(
        tmp_path,
        [
            {"lemma": "naufrage", "pos": "noun", "definition": "Perte d'un navire."},
            {"lemma": "naufragé", "pos": "adjective", "definition": "Qui a fait naufrage."},
        ],
    )
    summary = ba.backfill(conn, ba.load_lookup(dbnary), ba.load_pos_agnostic(dbnary))
    assert summary == {"updated": 0, "skipped": 1, "already": 0}
    assert _fetch_mot(conn, item_id) == "NAUFRAGE"


def test_pos_agnostic_fallback_does_not_touch_real_pos(conn, tmp_path):
    """The fallback fires only for autre/polyvalent; a real-POS miss is still skipped, not guessed."""
    item_id = _insert_item(conn, "ELEGANT", pos="nom_commun")
    dbnary = _dbnary(
        tmp_path,
        [{"lemma": "élégant", "pos": "adjective", "definition": "Qui a de l'élégance."}],
    )
    summary = ba.backfill(conn, ba.load_lookup(dbnary), ba.load_pos_agnostic(dbnary))
    assert summary == {"updated": 0, "skipped": 1, "already": 0}
    assert _fetch_mot(conn, item_id) == "ELEGANT"


def test_pos_filter_scopes_backfill(conn, tmp_path):
    """--pos restricts the walk: an autre row is touched, a nom_commun row of the same mot is not."""
    autre_id = _insert_item(conn, "FRERE", pos="autre")
    nom_id = _insert_item(conn, "FRERE", pos="nom_commun")
    dbnary = _dbnary(
        tmp_path,
        [{"lemma": "frère", "pos": "noun", "definition": "Fils des mêmes parents."}],
    )
    summary = ba.backfill(
        conn, ba.load_lookup(dbnary), ba.load_pos_agnostic(dbnary), pos_filter=["autre"]
    )
    assert summary == {"updated": 1, "skipped": 0, "already": 0}
    assert _fetch_mot(conn, autre_id) == "FRÈRE"
    assert _fetch_mot(conn, nom_id) == "FRERE"  # outside the pos filter, untouched


def test_backfill_dry_run_does_not_mutate(conn, tmp_path):
    """dry_run=True reports the update but leaves the row alone."""
    item_id = _insert_item(conn, "SOLDE", pos="adjectif")
    dbnary = _dbnary(
        tmp_path,
        [{"lemma": "soldé", "pos": "adjective", "definition": "Vendu au rabais."}],
    )
    lookup = ba.load_lookup(dbnary)

    summary = ba.backfill(conn, lookup, dry_run=True)
    assert summary == {"updated": 1, "skipped": 0, "already": 0}
    assert _fetch_mot(conn, item_id) == "SOLDE"  # unchanged
