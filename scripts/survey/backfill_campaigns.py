#!/usr/bin/env python3
"""Backfill campaigns from Modal-log CSVs and stamp historical ratings."""

from __future__ import annotations

import argparse
import csv
import sys
from dataclasses import dataclass
from datetime import datetime
from typing import Iterable

import psycopg
import uuid_utils


@dataclass(frozen=True)
class HistoricalBatch:
    batch_label: str
    opened_at: datetime
    closed_at: datetime


def parse_batches(path: str) -> list[HistoricalBatch]:
    out: list[HistoricalBatch] = []
    with open(path, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            out.append(
                HistoricalBatch(
                    batch_label=row["batch_label"].strip(),
                    opened_at=datetime.fromisoformat(row["opened_at"]),
                    closed_at=datetime.fromisoformat(row["closed_at"]),
                )
            )
    out.sort(key=lambda b: b.opened_at)
    for prev, curr in zip(out, out[1:]):
        if curr.opened_at < prev.closed_at:
            raise SystemExit(
                f"overlapping batches: {prev.batch_label} ends "
                f"{prev.closed_at}, {curr.batch_label} starts {curr.opened_at}"
            )
    return out


def ensure_campaigns(conn, batches: Iterable[HistoricalBatch], *, dry_run: bool) -> None:
    with conn.cursor() as cur:
        for batch in batches:
            cur.execute(
                "SELECT 1 FROM campaigns WHERE batch_label = %s",
                (batch.batch_label,),
            )
            if cur.fetchone() is not None:
                continue
            cur.execute(
                """
                INSERT INTO campaigns (campaign_id, batch_label, opened_at, closed_at)
                VALUES (%s, %s, %s, %s)
                """,
                (uuid_utils.uuid7(), batch.batch_label, batch.opened_at, batch.closed_at),
            )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dsn", required=True)
    parser.add_argument("--batches", required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)

    batches = parse_batches(args.batches)
    print(f"Loaded {len(batches)} historical batches from {args.batches}", file=sys.stderr)

    with psycopg.connect(args.dsn, autocommit=False) as conn:
        ensure_campaigns(conn, batches, dry_run=args.dry_run)
        stamp_ratings(conn, batches, dry_run=args.dry_run)
        stamp_pair_ratings(conn, batches, dry_run=args.dry_run)
        coverage_report(conn)
        if args.dry_run:
            conn.rollback()
            print("DRY RUN - rolled back.", file=sys.stderr)
        else:
            conn.commit()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
