#!/usr/bin/env python3
"""Re-stamp pleonastic clues across the production artifacts and runtime CSV.

The iter10 LoRA produced 6 pleonasm lemma clues that the original validator
missed (no head-not-lemma, no self-reference, no stem-leak — they're just
verbose tautologies):

    associer ensemble (lemma=unir)        Lier ensemble (lemma=enchaîner)
    Monter en haut    (lemma=gravir)      Ajouter ensemble (lemma=additionner)
    Prévoir à l'avance (lemma=anticiper)  Descendre en bas (lemma=baisser)

The agreement-aware inflater faithfully propagated the verbose form across
~70 surface entries in surface_clues.csv (e.g. "unir" → all 24 unir-family
verbs got "Associer ensemble"/"Associera ensemble"/...). Those 70 rows
landed in the runtime words-fr.csv and showed on the live wordsparrow.io
grid.

This script is the offline patch:
  1. Re-validate every row in the lemma_clues_*.csv tiers using the now
     pleonasm-aware validate_lemma_clue. Rows that were flagged 'ok' but
     trip the new gate get re-stamped to 'pleonasm'.
  2. Move pleonastic rows out of surface_clues.csv → surface_clues_dropped.csv.
  3. In the runtime words-fr.csv, replace the pleonastic clue with the
     surface form itself (the same placeholder that other clue-less words
     use). The grid renderer treats placeholder == surface as "no clue
     yet" — better an empty cell than a tautology.

Idempotent. Run with --dry-run first to inspect changes.

Usage:
    python scripts/clue_generation/strip_pleonastic_clues.py [--dry-run]
"""

from __future__ import annotations

import argparse
import csv
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts" / "eval"))

from validate_clue import _find_pleonasm  # noqa: E402

PRODUCTION = REPO / "data" / "eval" / "production"
LEMMA_TIERS = (
    PRODUCTION / "lemma_clues_raw.csv",
    PRODUCTION / "lemma_clues_raw_pos.csv",
    PRODUCTION / "lemma_clues_raw_pos_fixed.csv",
    PRODUCTION / "lemma_clues_shipped.csv",
    PRODUCTION / "lemma_clues_dropped.csv",
)
SURFACE_SHIPPED = PRODUCTION / "surface_clues.csv"
SURFACE_DROPPED = PRODUCTION / "surface_clues_dropped.csv"
WORDLIST = REPO / "grid/api/src/main/resources/words/words-fr.csv"


def _restamp_lemma_csv(path: Path, dry_run: bool) -> int:
    """Flip rows whose clue trips _find_pleonasm from 'ok' → 'pleonasm'.

    For lemma_clues_shipped.csv we additionally evict the row entirely
    (move to dropped tier — see _move_pleonasm_lemmas_from_shipped). Here
    we only re-stamp the validation_flag, which is enough for the next
    build_surface_clues run to skip the row."""
    rows: list[dict] = []
    flipped = 0
    with path.open(encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        fieldnames = reader.fieldnames or []
        for r in reader:
            clue = r.get("lemma_clue", "")
            if r.get("validation_flag") == "ok" and _find_pleonasm(clue):
                r["validation_flag"] = "pleonasm"
                flipped += 1
            rows.append(r)
    if flipped and not dry_run:
        with path.open("w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
            w.writeheader()
            w.writerows(rows)
    print(f"  {path.name}: {flipped} rows re-stamped to pleonasm")
    return flipped


def _move_pleonasm_lemmas_from_shipped(dry_run: bool) -> int:
    """Move pleonasm-flagged rows out of lemma_clues_shipped.csv into
    lemma_clues_dropped.csv so the shipped tier matches the new gate."""
    shipped = PRODUCTION / "lemma_clues_shipped.csv"
    dropped = PRODUCTION / "lemma_clues_dropped.csv"
    keep, evict = [], []
    with shipped.open(encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        fieldnames = reader.fieldnames or []
        for r in reader:
            (evict if r.get("validation_flag") == "pleonasm" else keep).append(r)
    if not evict:
        print(f"  shipped lemma corpus: nothing to evict")
        return 0
    if not dry_run:
        with shipped.open("w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
            w.writeheader()
            w.writerows(keep)
        # Append to dropped (preserve dropped header).
        with dropped.open(encoding="utf-8", newline="") as f:
            drop_fields = (csv.DictReader(f).fieldnames) or fieldnames
        with dropped.open("a", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=drop_fields, lineterminator="\n")
            for r in evict:
                w.writerow({k: r.get(k, "") for k in drop_fields})
    print(f"  shipped lemma corpus: evicted {len(evict)} pleonasm rows")
    return len(evict)


def _split_surface_clues(dry_run: bool) -> tuple[int, int]:
    """Move every shipped surface_clues row whose source_clue is pleonastic
    into the dropped tier. We key on `source_clue` (the lemma-form clue) —
    once that's pleonastic, every inflected surface inherits the bug."""
    if not SURFACE_SHIPPED.exists():
        return 0, 0
    keep, evict = [], []
    with SURFACE_SHIPPED.open(encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        fieldnames = reader.fieldnames or []
        for r in reader:
            source = r.get("source_clue", "") or r.get("clue", "")
            if _find_pleonasm(source):
                # Annotate the row so audits can see why it was evicted.
                r["validation_flag"] = "pleonasm"
                evict.append(r)
            else:
                keep.append(r)
    if dry_run:
        print(f"  surface_clues.csv: would evict {len(evict)} rows, keep {len(keep)}")
        return len(evict), 0
    with SURFACE_SHIPPED.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        w.writeheader()
        w.writerows(keep)
    # Read existing dropped tier (preserve its rows) and append.
    if SURFACE_DROPPED.exists():
        with SURFACE_DROPPED.open(encoding="utf-8", newline="") as f:
            existing = list(csv.DictReader(f))
            drop_fields = (csv.DictReader(open(SURFACE_DROPPED, encoding="utf-8")).fieldnames) or fieldnames
    else:
        existing = []
        drop_fields = fieldnames
    with SURFACE_DROPPED.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=drop_fields, lineterminator="\n")
        w.writeheader()
        w.writerows(existing)
        for r in evict:
            w.writerow({k: r.get(k, "") for k in drop_fields})
    print(f"  surface_clues.csv: evicted {len(evict)} → dropped (kept {len(keep)})")
    return len(evict), len(existing) + len(evict)


def _strip_pleonasms_from_runtime(dry_run: bool) -> int:
    """Replace pleonastic clues in words-fr.csv with the surface placeholder.

    Why placeholder rather than 'best alternative tail-stripped form'?
    The surface_clues.csv pipeline owns the alternative ranking (ADR-0024).
    Hand-editing the runtime CSV with derivative clues here would
    diverge it from the artifact. Reverting to placeholder restores the
    pre-PR-191 behaviour for those rows: SkeletonFiller renders them as
    blank slots until the next pipeline run picks a clean candidate."""
    if not WORDLIST.exists():
        return 0
    with WORDLIST.open(encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        fieldnames = reader.fieldnames or []
        rows = list(reader)
    flipped = 0
    for r in rows:
        clue = r.get("clue", "")
        if _find_pleonasm(clue):
            r["clue"] = r.get("word", "")
            flipped += 1
    if flipped and not dry_run:
        backup = WORDLIST.with_suffix(WORDLIST.suffix + ".prepleonasm.bak")
        shutil.copy2(WORDLIST, backup)
        with WORDLIST.open("w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
            w.writeheader()
            w.writerows(rows)
        print(f"  backup: {backup}")
    print(f"  words-fr.csv: {flipped} clues reverted to surface placeholder")
    return flipped


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true",
                        help="print the diff without writing")
    args = parser.parse_args()

    print("[1/4] re-stamping lemma corpus tiers...")
    for path in LEMMA_TIERS:
        if path.exists():
            _restamp_lemma_csv(path, args.dry_run)

    print("[2/4] evicting pleonasm rows from shipped lemma tier...")
    _move_pleonasm_lemmas_from_shipped(args.dry_run)

    print("[3/4] splitting surface_clues.csv...")
    _split_surface_clues(args.dry_run)

    print("[4/4] stripping pleonasms from runtime words-fr.csv...")
    _strip_pleonasms_from_runtime(args.dry_run)

    if args.dry_run:
        print("\n(dry-run; no files written)")


if __name__ == "__main__":
    main()
