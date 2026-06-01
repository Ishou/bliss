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
    # Fail loud: with no frequencies the tie-break degrades to _strip(), re-stripping the accents this backfill restores.
    if not path.exists():
        raise SystemExit(f"frequencies file required for the accent tie-break but absent: {path}")
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


def load_pos_agnostic(dbnary_path: Path) -> dict[str, str]:
    """{stripped_lemma: accented_surface}; homographs (multiple distinct surfaces) are omitted."""
    surfaces: dict[str, set[str]] = {}
    with dbnary_path.open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["pos"].strip() not in DBNARY_TO_ENUM:
                continue
            senses = [s for s in (row.get("definition") or "").split("|") if s.strip()]
            live = [s for s in senses if not _OBSOLETE.search(s)]
            if senses and not live:
                continue
            surfaces.setdefault(_strip(row["lemma"]), set()).add(row["lemma"])
    return {stripped: next(iter(v)) for stripped, v in surfaces.items() if len(v) == 1}


# legacy pos values with no DBNARY_TO_ENUM mapping; resolved POS-blind instead
_POS_AGNOSTIC_FALLBACK = frozenset({"autre", "polyvalent"})


def backfill(
    conn,
    lookup: dict[tuple[str, str], str],
    pos_agnostic: dict[str, str] | None = None,
    dry_run: bool = False,
    pos_filter: list[str] | None = None,
) -> dict[str, int]:
    """Walk survey_items, UPDATE mot for rows whose (stripped, pos) is in lookup."""
    pos_agnostic = pos_agnostic or {}
    with conn.cursor() as cur:
        if pos_filter:
            cur.execute(
                "SELECT item_id, mot, pos FROM survey_items WHERE retired_at IS NULL AND pos = ANY(%s)",
                (pos_filter,),
            )
        else:
            cur.execute("SELECT item_id, mot, pos FROM survey_items WHERE retired_at IS NULL")
        rows = cur.fetchall()
    updated = 0
    skipped = 0
    already = 0
    for item_id, mot, pos in rows:
        key = (_strip(mot), pos)
        canonical = lookup.get(key)
        if canonical is None and pos in _POS_AGNOSTIC_FALLBACK:
            canonical = pos_agnostic.get(_strip(mot))
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
    p.add_argument(
        "--pos",
        help="Comma-separated pos values to restrict the backfill to "
        "(e.g. 'autre,polyvalent' for the legacy buckets); default: all live rows.",
    )
    args = p.parse_args()

    pos_filter = [p.strip() for p in args.pos.split(",")] if args.pos else None
    lookup = load_lookup(args.dbnary, load_frequencies(args.frequencies))
    pos_agnostic = load_pos_agnostic(args.dbnary)
    with psycopg.connect(args.dsn) as conn:
        summary = backfill(conn, lookup, pos_agnostic, dry_run=args.dry_run, pos_filter=pos_filter)
    print(f"updated={summary['updated']} skipped={summary['skipped']} already={summary['already']}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
