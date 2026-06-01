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


def _insert_item(conn, mot: str, pos: str = "ADJECTIF") -> _uuid.UUID:
    # tier omitted: V1's CHECK constraint requires lowercase ('high','mid','low','excluded'); rely on the 'mid' default.
    item_id = _uuid.uuid4()
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO survey_items
              (item_id, mot, definition, pos, categorie, style,
               force_claimed, longueur, source, source_batch)
            VALUES (%s, %s, 'def', %s, 'AUTRE', 'DEFINITION_DIRECTE',
                    1, length(%s), 'SYNTHETIC_V1', 'test')
            """,
            (item_id, mot, pos, mot),
        )
    conn.commit()
    return item_id


def _fetch_mot(conn, item_id: _uuid.UUID) -> str:
    with conn.cursor() as cur:
        cur.execute("SELECT mot FROM survey_items WHERE item_id = %s", (item_id,))
        return cur.fetchone()[0]
