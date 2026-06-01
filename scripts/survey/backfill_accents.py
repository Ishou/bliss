#!/usr/bin/env python3
"""Backfill survey_items.mot from ASCII to accented uppercase via DBnary lookup."""

from __future__ import annotations

import argparse
import csv
import sys
import unicodedata
from pathlib import Path

import psycopg


# Mirrors build_pos_lemmas._strip — keep in sync.
def _strip(s: str) -> str:
    return "".join(
        c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn"
    ).lower()


# DBnary POS -> survey_items.pos wire format (lowercase).
DBNARY_TO_ENUM = {
    "noun": "nom_commun",
    "properNoun": "nom_propre",
    "adjective": "adjectif",
    "verb": "verbe_infinitif",
    "adverb": "adverbe",
}


def load_lookup(dbnary_path: Path) -> dict[tuple[str, str], str]:
    """Build {(stripped_lemma, survey_pos_enum): accented_lemma} from DBnary."""
    lookup: dict[tuple[str, str], str] = {}
    with dbnary_path.open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            pos_dbnary = row["pos"].strip()
            if pos_dbnary not in DBNARY_TO_ENUM:
                continue
            survey_pos = DBNARY_TO_ENUM[pos_dbnary]
            key = (_strip(row["lemma"]), survey_pos)
            # Alphabetical tie-break — matches build_pos_lemmas.
            existing = lookup.get(key)
            if existing is None or row["lemma"] < existing:
                lookup[key] = row["lemma"]
    return lookup


def backfill(conn, lookup: dict[tuple[str, str], str], dry_run: bool = False) -> dict[str, int]:
    """Walk survey_items, UPDATE mot for rows whose (stripped, pos) is in lookup."""
    updated = 0
    skipped = 0
    already = 0
    with conn.cursor() as cur:
        cur.execute("SELECT item_id, mot, pos FROM survey_items WHERE retired_at IS NULL")
        rows = cur.fetchall()
    for item_id, mot, pos in rows:
        key = (_strip(mot), pos)
        canonical = lookup.get(key)
        if canonical is None:
            print(f"skip (no DBnary match): {mot} ({pos}) item_id={item_id}", file=sys.stderr)
            skipped += 1
            continue
        target = canonical.upper()
        if target == mot:
            already += 1
            continue
        if not dry_run:
            with conn.cursor() as cur:
                cur.execute(
                    "UPDATE survey_items SET mot = %s WHERE item_id = %s",
                    (target, item_id),
                )
        print(f"{'(dry) ' if dry_run else ''}{mot} -> {target} ({pos}) item_id={item_id}", file=sys.stderr)
        updated += 1
    if not dry_run:
        conn.commit()
    return {"updated": updated, "skipped": skipped, "already": already}


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dsn", required=True, help="libpq DSN for survey-api Postgres")
    p.add_argument("--dbnary", type=Path, default=Path("data/dbnary/dbnary_fr.csv"))
    p.add_argument("--dry-run", action="store_true", help="Print updates without applying.")
    args = p.parse_args()

    lookup = load_lookup(args.dbnary)
    with psycopg.connect(args.dsn) as conn:
        summary = backfill(conn, lookup, dry_run=args.dry_run)
    print(f"updated={summary['updated']} skipped={summary['skipped']} already={summary['already']}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
