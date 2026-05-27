"""Tests for extract_winners — SQLite in-memory shim stands in for Postgres."""

from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

import pytest

from . import extract_winners as ew


USER_ME = "11111111-1111-4111-8111-111111111111"
USER_OTHER = "22222222-2222-4222-8222-222222222222"


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
    """In-memory SQLite seeded with 5 survey_items + ratings; only one row matches all filters."""
    conn = sqlite3.connect(":memory:")
    conn.execute("""
        CREATE TABLE survey_items (
            item_id TEXT PRIMARY KEY,
            mot TEXT, definition TEXT, pos TEXT, categorie TEXT, style TEXT,
            force_claimed INTEGER, longueur INTEGER,
            source TEXT, source_batch TEXT, expected TEXT, retired_at TEXT
        )
    """)
    conn.execute("""
        CREATE TABLE ratings (
            rating_id TEXT PRIMARY KEY, item_id TEXT, user_id TEXT,
            qualite INTEGER, flag TEXT, created_at TEXT
        )
    """)

    items = [
        # (item_id, mot, def, pos, cat, style, force, longueur, source, batch, expected, retired_at)
        # round lineage now lives in source_batch (`-r<N>-` infix); source is the Kotlin enum value.
        ("i1", "PAIN", "Aliment de boulangerie", "nom_commun", "aliments",
         "definition_directe", 1, 4, "synthetic_v1", "mistral-nemo-pilot-v1-r1-aaa", None, None),
        ("i2", "TRAIN", "Transport ferroviaire", "nom_commun", "transports",
         "definition_directe", 1, 5, "synthetic_v1", "mistral-nemo-pilot-v1-r2-bbb", None, None),
        ("i3", "MIEL", "Produit des abeilles", "nom_commun", "aliments",
         "definition_directe", 2, 4, "synthetic_v1", "mistral-nemo-pilot-v1-r1-aaa", None,
         "2026-05-26T10:00:00+00:00"),
        ("i4", "EAU", "Liquide vital", "nom_commun", "boissons",
         "definition_directe", 1, 3, "gold", "gold_v1", None, None),
        ("i5", "SEL", "Condiment marin", "nom_commun", "aliments",
         "definition_directe", 1, 3, "synthetic_v1", "mistral-nemo-pilot-v1-r1-aaa", None, None),
    ]
    conn.executemany(
        "INSERT INTO survey_items VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        items,
    )

    ratings = [
        ("r1", "i1", USER_ME, 5, None, "2026-05-26T12:00:00+00:00"),       # KEEP (batch matches -r1-)
        ("r2", "i2", USER_ME, 5, None, "2026-05-26T12:01:00+00:00"),       # batch is -r2- → SQL filters out when --round 1
        ("r3", "i3", USER_ME, 5, None, "2026-05-26T12:02:00+00:00"),       # retired → SQL filters out
        ("r4", "i4", USER_ME, 5, None, "2026-05-26T12:03:00+00:00"),       # gold_v1 batch (no -r1-) → SQL filters out
        ("r5", "i5", USER_ME, 3, None, "2026-05-26T12:04:00+00:00"),       # qualite=3 → SQL filters out
        ("r6", "i1", USER_ME, 5, "hors_sujet", "2026-05-26T12:05:00+00:00"),  # flag set → SQL filters out
        ("r7", "i1", USER_OTHER, 5, None, "2026-05-26T12:06:00+00:00"),    # other user → SQL filters out
    ]
    conn.executemany(
        "INSERT INTO ratings VALUES (?,?,?,?,?,?)",
        ratings,
    )
    conn.commit()
    return SqliteConnAdapter(conn)


def test_run_writes_only_matching_row(seeded_conn, tmp_path):
    """End-to-end: only PAIN (source_batch -r1-, qualite=5, flag NULL, unretired) survives."""
    out = tmp_path / "winners.jsonl"
    result = ew.run(USER_ME, 1, out, seeded_conn)
    assert result["kept"] == 1

    lines = out.read_text(encoding="utf-8").splitlines()
    assert len(lines) == 1
    entry = json.loads(lines[0])
    assert entry == {
        "mot": "PAIN",
        "definition": "Aliment de boulangerie",
        "pos": "nom_commun",
        "categorie": "aliments",
        "style": "definition_directe",
        "force": 1,
        "longueur": 4,
        "source": "synthetic_v1",
        "source_batch": "mistral-nemo-pilot-v1-r1-aaa",
        "rated_at": "2026-05-26T12:00:00+00:00",
    }


def test_run_with_round_2_keeps_only_train(seeded_conn, tmp_path):
    """Switching --round 2 keeps TRAIN (source_batch -r2-) and excludes PAIN via SQL."""
    out = tmp_path / "winners.jsonl"
    result = ew.run(USER_ME, 2, out, seeded_conn)
    assert result["kept"] == 1
    entry = json.loads(out.read_text(encoding="utf-8").splitlines()[0])
    assert entry["mot"] == "TRAIN"


def test_row_to_entry_isoformats_datetime():
    """`rated_at` is ISO-8601 even when the driver returns a datetime."""
    dt = datetime(2026, 5, 26, 12, 0, 0, tzinfo=timezone.utc)
    row = ("CHAT", "Animal", "nom_commun", "animaux", "definition_directe",
           1, 4, "synthetic_v1", "mistral-nemo-pilot-v1-r1-aaa", None, 5, USER_ME, dt)
    entry, reason = ew.row_to_entry(row, 1)
    assert reason is None
    assert entry["rated_at"] == "2026-05-26T12:00:00+00:00"


def test_row_to_entry_drops_wrong_round():
    """A row whose source_batch carries -r3- is dropped when --round=1."""
    row = ("X", "d", "p", "c", "s", 1, 1, "synthetic_v1", "mistral-nemo-pilot-v1-r3-zzz", None, 5, USER_ME, "t")
    entry, reason = ew.row_to_entry(row, 1)
    assert entry is None
    assert reason == "source_batch_round_mismatch"


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
