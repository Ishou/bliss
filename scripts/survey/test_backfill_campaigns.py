"""Integration tests for backfill_campaigns; requires Docker (Testcontainers)."""

from __future__ import annotations

import sys
from datetime import datetime, timezone
from pathlib import Path

import psycopg
import pytest
from testcontainers.postgres import PostgresContainer

sys.path.insert(0, str(Path(__file__).parent))

import backfill_campaigns as bf  # noqa: E402

_REPO_ROOT = Path(__file__).resolve().parents[2]
_MIGRATIONS = _REPO_ROOT / "survey/infrastructure/src/main/resources/db/migration"


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
        with c.cursor() as cur:
            cur.execute("TRUNCATE campaigns, ratings, pair_ratings, survey_items CASCADE")
        c.commit()


def _at(s: str) -> datetime:
    return datetime.fromisoformat(s).replace(tzinfo=timezone.utc)


def test_ensure_campaigns_inserts_one_row_per_batch(conn):
    batches = [
        bf.HistoricalBatch("round-5", _at("2026-05-01T00:00:00"), _at("2026-05-07T00:00:00")),
        bf.HistoricalBatch("round-6", _at("2026-05-08T00:00:00"), _at("2026-05-15T00:00:00")),
    ]
    bf.ensure_campaigns(conn, batches, dry_run=False)
    with conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM campaigns")
        assert cur.fetchone()[0] == 2


def test_ensure_campaigns_is_idempotent(conn):
    batches = [bf.HistoricalBatch("round-5", _at("2026-05-01T00:00:00"), _at("2026-05-07T00:00:00"))]
    bf.ensure_campaigns(conn, batches, dry_run=False)
    bf.ensure_campaigns(conn, batches, dry_run=False)
    with conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM campaigns WHERE batch_label = 'round-5'")
        assert cur.fetchone()[0] == 1
