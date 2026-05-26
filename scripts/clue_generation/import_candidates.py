from __future__ import annotations

import argparse
import json
import os
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

INSERT_SQL = """
INSERT INTO survey_items
    (item_id, mot, definition, pos, categorie, style, force_claimed, longueur,
     source, source_batch, tier, is_calibration, expected, retired_at, created_at)
VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, FALSE, NULL, NULL, %s)
ON CONFLICT (mot, definition) WHERE retired_at IS NULL DO NOTHING
"""


def _connect():
    """psycopg2 connect via SURVEY_DB_URL env var or ~/.bliss/survey-db-url file."""
    import psycopg2
    url = os.environ.get("SURVEY_DB_URL")
    if not url:
        cred_file = Path.home() / ".bliss" / "survey-db-url"
        if cred_file.exists():
            url = cred_file.read_text().strip()
    if not url:
        sys.exit("set SURVEY_DB_URL env var or write ~/.bliss/survey-db-url")
    return psycopg2.connect(url)


def main() -> int:
    p = argparse.ArgumentParser(description="Import Modal-generated candidates JSONL into the survey-api survey_items table.")
    p.add_argument("--jsonl", type=Path, required=True, help="candidates.jsonl from 04_generate.py")
    p.add_argument("--source-batch", required=True, help="e.g., round_1")
    p.add_argument("--tier", default="mid", choices=["high", "mid", "low", "excluded"])
    p.add_argument("--dry-run", action="store_true", help="parse + count, no DB writes")
    args = p.parse_args()

    rows = [json.loads(line) for line in args.jsonl.read_text().splitlines() if line.strip()]
    print(f"parsed {len(rows)} candidates from {args.jsonl}", file=sys.stderr)
    if args.dry_run:
        return 0

    now = datetime.now(timezone.utc)
    inserted = 0
    skipped = 0
    with _connect() as conn:
        with conn.cursor() as cur:
            for row in rows:
                cur.execute(
                    INSERT_SQL,
                    (
                        str(uuid.uuid4()),
                        row["mot"],
                        row["definition"],
                        row["pos"],
                        row["categorie"],
                        row["style"],
                        int(row.get("force_estimated", 3)),
                        int(row["longueur"]),
                        row["source"],
                        args.source_batch,
                        args.tier,
                        now,
                    ),
                )
                if cur.rowcount == 1:
                    inserted += 1
                else:
                    skipped += 1
        conn.commit()
    print(f"inserted={inserted} skipped_dup={skipped}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
