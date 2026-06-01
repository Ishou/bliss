#!/usr/bin/env python3
"""Backfill survey_items.mot from ASCII to accented uppercase via DBnary lookup."""

from __future__ import annotations

import argparse
import csv
import re
import sys
import unicodedata
from pathlib import Path

import psycopg

_DEFAULT_FREQUENCIES = Path("grid/infrastructure/src/main/resources/words/words-fr.csv")

# A (lemma, pos) whose every sense carries an obsolete tag is dropped; gloss text is never emitted.
_OBSOLETE = re.compile(r"\((?:Désuet|Vieilli|Vieux|Archaïsme|Archaïque|Par archaïsme)\)", re.IGNORECASE)


# Must produce the same key used when building the lookup; any divergence silently drops rows.
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


def load_frequencies(path: Path) -> dict[str, int]:
    """Build {lowercased_surface: max grammalecte frequency} from words-fr.csv."""
    freqs: dict[str, int] = {}
    with path.open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            word = (row.get("word") or "").lower()
            if not word:
                continue
            try:
                freq = int(row["frequency"])
            except (KeyError, ValueError):
                continue
            if freq > freqs.get(word, -1):
                freqs[word] = freq
    return freqs


def _resolve(variants: set[str], frequencies: dict[str, int]) -> str:
    """Pick the canonical spelling: lone non-désuet wins, else highest grammalecte freq, else stripped."""
    if len(variants) == 1:
        return next(iter(variants))
    present = sorted(
        ((frequencies[v.lower()], v) for v in variants if v.lower() in frequencies),
        key=lambda t: (-t[0], t[1]),
    )
    if present:
        return present[0][1]
    return _strip(next(iter(variants)))


def load_lookup(dbnary_path: Path, frequencies: dict[str, int] | None = None) -> dict[tuple[str, str], str]:
    """Build {(stripped_lemma, survey_pos_enum): accented_lemma} from DBnary."""
    frequencies = frequencies or {}
    candidates: dict[tuple[str, str], set[str]] = {}
    with dbnary_path.open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            pos_dbnary = row["pos"].strip()
            if pos_dbnary not in DBNARY_TO_ENUM:
                continue
            senses = [s for s in (row.get("definition") or "").split("|") if s.strip()]
            live = [s for s in senses if not _OBSOLETE.search(s)]
            if senses and not live:
                continue
            key = (_strip(row["lemma"]), DBNARY_TO_ENUM[pos_dbnary])
            candidates.setdefault(key, set()).add(row["lemma"])
    return {key: _resolve(variants, frequencies) for key, variants in candidates.items()}


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
    p.add_argument("--frequencies", type=Path, default=_DEFAULT_FREQUENCIES)
    p.add_argument("--dry-run", action="store_true", help="Print updates without applying.")
    args = p.parse_args()

    lookup = load_lookup(args.dbnary, load_frequencies(args.frequencies))
    with psycopg.connect(args.dsn) as conn:
        summary = backfill(conn, lookup, dry_run=args.dry_run)
    print(f"updated={summary['updated']} skipped={summary['skipped']} already={summary['already']}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
