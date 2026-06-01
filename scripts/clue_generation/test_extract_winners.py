"""Tests for extract_winners — SQLite in-memory shim stands in for Postgres."""

from __future__ import annotations

import csv
import io
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

import pytest

from . import extract_winners as ew


USER_ME = "11111111-1111-4111-8111-111111111111"
USER_OTHER = "22222222-2222-4222-8222-222222222222"

CAMP_LAST = "33333333-3333-4333-8333-333333333333"   # most-recent, closed
CAMP_OLD = "44444444-4444-4444-8444-444444444444"     # earlier, closed


class SqliteConnAdapter:
    """Wrap sqlite3.Connection so its cursor matches the psycopg2 context-manager API."""

    def __init__(self, conn: sqlite3.Connection) -> None:
        self._conn = conn

    def cursor(self) -> "SqliteCursorAdapter":
        return SqliteCursorAdapter(self._conn.cursor())


class SqliteCursorAdapter:
    """Translate %s placeholders to ? and drop ::uuid casts for SQLite."""

    def __init__(self, cur: sqlite3.Cursor) -> None:
        self._cur = cur

    def __enter__(self) -> "SqliteCursorAdapter":
        return self

    def __exit__(self, *exc) -> None:
        self._cur.close()

    def execute(self, sql: str, params: tuple) -> None:
        sql_sqlite = sql.replace("%s::uuid", "?").replace("%s", "?").replace("%%", "%")
        self._cur.execute(sql_sqlite, params)

    def fetchall(self) -> list:
        return list(self._cur.fetchall())


@pytest.fixture
def seeded_conn() -> SqliteConnAdapter:
    """In-memory SQLite: items + ratings + maintainer_roles; mixed RAFT + correctif rows."""
    conn = sqlite3.connect(":memory:")
    conn.execute("""
        CREATE TABLE survey_items (
            item_id TEXT PRIMARY KEY,
            mot TEXT, definition TEXT, pos TEXT, categorie TEXT, style TEXT,
            force_claimed INTEGER, longueur INTEGER,
            source TEXT, source_batch TEXT, expected TEXT, retired_at TEXT,
            training_weight REAL DEFAULT 1.0
        )
    """)
    conn.execute("""
        CREATE TABLE ratings (
            rating_id TEXT PRIMARY KEY, item_id TEXT, user_id TEXT,
            qualite INTEGER, flag TEXT, proposed_item_id TEXT,
            created_at TEXT, campaign_id TEXT
        )
    """)
    conn.execute("""
        CREATE TABLE campaigns (
            campaign_id TEXT PRIMARY KEY, batch_label TEXT,
            opened_at TEXT, closed_at TEXT
        )
    """)
    conn.executemany(
        "INSERT INTO campaigns VALUES (?,?,?,?)",
        [
            (CAMP_OLD, "round-8", "2026-05-25T09:00:00+00:00", "2026-05-25T13:00:00+00:00"),
            (CAMP_LAST, "round-9", "2026-05-26T09:00:00+00:00", "2026-05-26T13:00:00+00:00"),
        ],
    )
    conn.execute("""
        CREATE TABLE maintainer_roles (
            user_id TEXT PRIMARY KEY, role TEXT, changed_at TEXT
        )
    """)
    conn.execute(
        "INSERT INTO maintainer_roles VALUES (?, 'maintainer', '2026-05-01T00:00:00+00:00')",
        (USER_ME,),
    )

    items = [
        # (item_id, mot, def, pos, cat, style, force, longueur, source, batch, expected, retired_at, training_weight)
        ("i1", "PAIN", "Aliment de boulangerie", "nom_commun", "aliments",
         "definition_directe", 1, 4, "synthetic_v1", "c4ai-command-r-pilot-v1-r9-aaa", None, None, 1.0),
        ("i2", "TRAIN", "Transport ferroviaire", "nom_commun", "transports",
         "definition_directe", 1, 5, "synthetic_v1", "c4ai-command-r-pilot-v1-r8-bbb", None, None, 1.0),
        ("i3", "MIEL", "Produit des abeilles", "nom_commun", "aliments",
         "definition_directe", 2, 4, "synthetic_v1", "c4ai-command-r-pilot-v1-r9-aaa", None,
         "2026-05-26T10:00:00+00:00", 1.0),
        ("i4", "EAU", "Liquide vital", "nom_commun", "boissons",
         "definition_directe", 1, 3, "gold", "gold_v1", None, None, 1.0),
        ("i5", "SEL", "Condiment marin", "nom_commun", "aliments",
         "definition_directe", 1, 3, "synthetic_v1", "c4ai-command-r-pilot-v1-r9-aaa", None, None, 1.0),
        # rater_proposed items: i6 gold (training_weight=3), i7 non-gold (weight=1)
        ("i6", "CHAT", "Petit félin domestique", "nom_commun", "animaux",
         "definition_directe", 2, 4, "rater_proposed", "rater_2026-06", None, None, 3.0),
        ("i7", "CHIEN", "Compagnon fidèle", "nom_commun", "animaux",
         "definition_directe", 2, 5, "rater_proposed", "rater_2026-05", None, None, 1.0),
    ]
    conn.executemany(
        "INSERT INTO survey_items VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        items,
    )

    ratings = [
        # r9 RAFT path — scoped to the last (closed) campaign
        ("r1", "i1", USER_ME, 5, None, None, "2026-05-26T12:00:00+00:00", CAMP_LAST),   # KEEP: r9, qualite=5, last campaign
        ("r2", "i2", USER_ME, 5, None, None, "2026-05-26T12:01:00+00:00", CAMP_LAST),   # DROP: r8 (round mismatch)
        ("r3", "i3", USER_ME, 5, None, None, "2026-05-26T12:02:00+00:00", CAMP_LAST),   # DROP: retired
        ("r4", "i4", USER_ME, 5, None, None, "2026-05-26T12:03:00+00:00", CAMP_LAST),   # DROP: gold_v1 batch
        ("r5", "i5", USER_ME, 3, None, None, "2026-05-26T12:04:00+00:00", CAMP_LAST),   # DROP: qualite=3
        ("r6", "i1", USER_ME, 5, "hors_sujet", None, "2026-05-26T12:05:00+00:00", CAMP_LAST),  # DROP: flag set
        ("r7", "i1", USER_OTHER, 5, None, None, "2026-05-26T12:06:00+00:00", CAMP_LAST),  # DROP: not a maintainer
        # rater_proposed auto-GOOD path — cumulative, NOT campaign-scoped (older campaign / NULL still kept)
        ("r8", "i6", USER_ME, 5, None, None, "2026-05-30T08:31:00+00:00", CAMP_OLD),    # KEEP: gold correctif, earlier campaign
        ("r9", "i7", USER_ME, 5, None, None, "2026-05-29T08:31:00+00:00", None),        # KEEP: non-gold correctif, NULL campaign
        ("r10", "i7", USER_ME, 5, None, "i6", "2026-05-29T08:32:00+00:00", None),       # DROP: nested correctif (proposed_item_id set)
    ]
    conn.executemany(
        "INSERT INTO ratings VALUES (?,?,?,?,?,?,?,?)",
        ratings,
    )
    conn.commit()
    return SqliteConnAdapter(conn)


def _read_csv(path: Path) -> list[dict]:
    """Read a `;`-delimited CSV into a list of dicts."""
    return list(csv.DictReader(io.StringIO(path.read_text(encoding="utf-8")), delimiter=";"))


def test_run_writes_r9_winner_and_correctifs(seeded_conn, tmp_path):
    """Round 9 keeps PAIN (RAFT) + CHAT (gold correctif) + CHIEN (non-gold correctif)."""
    out = tmp_path / "winners.csv"
    result = ew.run(9, out, seeded_conn)
    assert result["kept"] == 3
    rows = _read_csv(out)
    mots = {r["mot"] for r in rows}
    assert mots == {"PAIN", "CHAT", "CHIEN"}


def test_csv_carries_training_weight(seeded_conn, tmp_path):
    """The gold correctif CHAT carries training_weight=3.0 in the CSV."""
    out = tmp_path / "winners.csv"
    ew.run(9, out, seeded_conn)
    rows = _read_csv(out)
    by_mot = {r["mot"]: r for r in rows}
    assert by_mot["CHAT"]["training_weight"] == "3.0"
    assert by_mot["CHIEN"]["training_weight"] == "1.0"
    assert by_mot["PAIN"]["training_weight"] == "1.0"


def test_csv_uses_force_column_not_force_claimed(seeded_conn, tmp_path):
    """The CSV uses `force` as the column name (manifest schema_mapping expects this)."""
    out = tmp_path / "winners.csv"
    ew.run(9, out, seeded_conn)
    rows = _read_csv(out)
    assert "force" in rows[0]
    assert "force_claimed" not in rows[0]


def test_no_maintainer_means_no_rows(tmp_path):
    """When no user is tagged as maintainer, nothing is exported (instead of all users)."""
    conn = sqlite3.connect(":memory:")
    conn.execute("CREATE TABLE survey_items (item_id TEXT, mot TEXT, definition TEXT, pos TEXT, "
                 "categorie TEXT, style TEXT, force_claimed INTEGER, longueur INTEGER, "
                 "source TEXT, source_batch TEXT, expected TEXT, retired_at TEXT, training_weight REAL)")
    conn.execute("CREATE TABLE ratings (rating_id TEXT, item_id TEXT, user_id TEXT, "
                 "qualite INTEGER, flag TEXT, proposed_item_id TEXT, created_at TEXT, campaign_id TEXT)")
    conn.execute("CREATE TABLE campaigns (campaign_id TEXT, batch_label TEXT, opened_at TEXT, closed_at TEXT)")
    conn.execute("CREATE TABLE maintainer_roles (user_id TEXT, role TEXT, changed_at TEXT)")
    conn.execute("INSERT INTO campaigns VALUES (?, 'round-9', '2026-05-26T09:00:00+00:00', '2026-05-26T13:00:00+00:00')",
                 (CAMP_LAST,))
    conn.execute("INSERT INTO survey_items VALUES "
                 "('i1','X','d','p','c','s',1,1,'synthetic_v1','c4ai-command-r-pilot-v1-r9-aaa',NULL,NULL,1.0)")
    conn.execute("INSERT INTO ratings VALUES ('r1','i1',?,5,NULL,NULL,'2026-05-26T12:00:00+00:00',?)",
                 (USER_ME, CAMP_LAST))
    conn.commit()
    out = tmp_path / "winners.csv"
    result = ew.run(9, out, SqliteConnAdapter(conn))
    assert result["kept"] == 0


def test_run_refuses_when_latest_campaign_open(seeded_conn, tmp_path):
    """A newer open campaign blocks extraction — the latest window must be closed first (ADR-0059)."""
    seeded_conn._conn.execute(
        "INSERT INTO campaigns VALUES (?, 'round-10', '2026-05-27T09:00:00+00:00', NULL)",
        ("55555555-5555-4555-8555-555555555555",),
    )
    seeded_conn._conn.commit()
    out = tmp_path / "winners.csv"
    with pytest.raises(SystemExit, match="still open"):
        ew.run(9, out, seeded_conn)


def test_run_refuses_when_no_campaigns(seeded_conn, tmp_path):
    """No campaign rows at all → refuse rather than emit an unscoped corpus."""
    seeded_conn._conn.execute("DELETE FROM campaigns")
    seeded_conn._conn.commit()
    out = tmp_path / "winners.csv"
    with pytest.raises(SystemExit, match="no campaign"):
        ew.run(9, out, seeded_conn)


def test_raft_winner_from_earlier_campaign_is_excluded(seeded_conn, tmp_path):
    """A r9 RAFT winner rated under an earlier campaign is dropped; correctifs (cumulative) are not."""
    seeded_conn._conn.execute(
        "INSERT INTO survey_items VALUES "
        "('i8','OURS','Grand mammifère','nom_commun','animaux','definition_directe',"
        "2,4,'synthetic_v1','c4ai-command-r-pilot-v1-r9-zzz',NULL,NULL,1.0)"
    )
    seeded_conn._conn.execute(
        "INSERT INTO ratings VALUES ('r11','i8',?,5,NULL,NULL,'2026-05-25T12:00:00+00:00',?)",
        (USER_ME, CAMP_OLD),
    )
    seeded_conn._conn.commit()
    out = tmp_path / "winners.csv"
    result = ew.run(9, out, seeded_conn)
    mots = {r["mot"] for r in _read_csv(out)}
    assert "OURS" not in mots               # r9 RAFT row outside the last campaign is excluded
    assert mots == {"PAIN", "CHAT", "CHIEN"}
    assert result["kept"] == 3


def test_row_to_entry_isoformats_datetime():
    """`rated_at` is ISO-8601 even when the driver returns a datetime."""
    dt = datetime(2026, 5, 26, 12, 0, 0, tzinfo=timezone.utc)
    row = ("CHAT", "Animal", "nom_commun", "animaux", "definition_directe",
           1, 4, "synthetic_v1", "c4ai-command-r-pilot-v1-r9-aaa", 1.0,
           5, dt)
    entry = ew.row_to_entry(row)
    assert entry["rated_at"] == "2026-05-26T12:00:00+00:00"
    assert entry["mot"] == "CHAT"


def test_load_db_url_errors_when_neither_source_present(monkeypatch, tmp_path):
    """No env var, no fallback file → clear SystemExit, not a stdin prompt."""
    monkeypatch.delenv("SURVEY_DB_URL", raising=False)
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    with pytest.raises(SystemExit, match="no DB URL"):
        ew._load_db_url()


def test_load_db_url_prefers_env(monkeypatch):
    """Env var wins over file even when file exists."""
    monkeypatch.setenv("SURVEY_DB_URL", "postgresql://env/db?sslmode=require")
    assert ew._load_db_url() == "postgresql://env/db?sslmode=require"
