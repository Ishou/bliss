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


pytestmark = pytest.mark.skipif(not _docker_available(), reason="Docker daemon not available")


@pytest.fixture(scope="module")
def pg():
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


def _insert_item(conn, mot: str, pos: str = "adjectif") -> _uuid.UUID:
    # tier omitted: V1's CHECK constraint requires lowercase ('high','mid','low','excluded'); rely on the 'mid' default.
    # All other enum-backed columns (pos, categorie, style, source) are stored lowercase in prod — see PgSurveyItemRepository.kt + StyleGuideCsvWriter.kt.
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
