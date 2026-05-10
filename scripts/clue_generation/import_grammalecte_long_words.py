#!/usr/bin/env python3
"""Append long-length grammalecte surfaces (and their lemmas) to words-fr.csv.

The runtime corpus has historically capped at length 11; PRs #356/#357 push
the daily-grid default to 15×12 and need supply at lengths 12-15. The
upstream grammalecte lexique has 50k+ surfaces at L12, 38k at L13, 24k at
L14, and 14k at L15 (alphabetic, freq≥1000), but none of them are in the
checked-in CSV.

This script ports the relevant rows over with placeholder clues
(`clue == word`). The downstream pipeline can later replace those
placeholders with authored clues via the existing
generate-clues-lora-batched → build-surface-clues → merge-clues-into-wordlist
flow; in the interim, the grid generator just needs supply, and the
runtime CSV loader (CsvWordRepository) keeps any row whose `clue` is
non-blank — `clue == word` qualifies.

Idempotent: re-running with the same flags is a no-op (existing words
are not re-appended). The output keeps the input column order and
appends new rows at the tail; reorder/sort is the caller's choice.

The `difficulty` column is left empty for new rows (matching
`add_short_word_clues.py`'s convention) — the runtime generator does not
read it. The `source` column is `grammalecte` and `source_license` is
`MPL-2.0`, mirroring how the existing length-≤11 grammalecte rows were
provenanced before the deleted bliss-worker `import-grammalecte`
subcommand stopped re-emitting them (PR #221).
"""
from __future__ import annotations
import argparse
import csv
import os
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
DEFAULT_LEXIQUE = Path(os.path.expanduser(
    "~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt"))
DEFAULT_WORDLIST = REPO / "grid/api/src/main/resources/words/words-fr.csv"


def parse_grammalecte(
    lexique: Path,
    length_min: int,
    length_max: int,
    min_freq: int,
) -> dict[str, tuple[str, int]]:
    """Return surface -> (lemma, frequency).

    When grammalecte tags a surface against multiple lemmas (homography),
    the pair with the highest `Total occurrences` wins. Ties keep
    whichever lemma was seen first; the file is freq-sorted so ties are
    rare and the choice is stable.
    """
    out: dict[str, tuple[str, int]] = {}
    seen_header = False
    flex_idx = lemme_idx = tot_idx = -1
    with lexique.open(encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            cols = line.rstrip("\n").split("\t")
            if not seen_header:
                if "Flexion" in cols and "Lemme" in cols and "Total occurrences" in cols:
                    flex_idx = cols.index("Flexion")
                    lemme_idx = cols.index("Lemme")
                    tot_idx = cols.index("Total occurrences")
                    seen_header = True
                continue
            if len(cols) <= max(flex_idx, lemme_idx, tot_idx):
                continue
            flex = cols[flex_idx]
            if not flex.isalpha():
                continue
            L = len(flex)
            if not (length_min <= L <= length_max):
                continue
            try:
                freq = int(cols[tot_idx])
            except ValueError:
                continue
            if freq < min_freq:
                continue
            lemma = cols[lemme_idx]
            existing = out.get(flex)
            if existing is None or freq > existing[1]:
                out[flex] = (lemma, freq)
    return out


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--lexique", type=Path, default=DEFAULT_LEXIQUE)
    p.add_argument("--wordlist", type=Path, default=DEFAULT_WORDLIST)
    p.add_argument("--length-min", type=int, default=12)
    p.add_argument("--length-max", type=int, default=15)
    p.add_argument("--min-freq", type=int, default=1000,
                   help="Skip surfaces with fewer than this many Total occurrences "
                        "(matches CsvWordRepository.MIN_FREQUENCY = 1000).")
    p.add_argument("--language", default="fr")
    p.add_argument("--source", default="grammalecte")
    p.add_argument("--source-license", default="MPL-2.0")
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args()

    if not args.lexique.exists():
        raise SystemExit(f"grammalecte lexique not found: {args.lexique}")
    if not args.wordlist.exists():
        raise SystemExit(f"wordlist not found: {args.wordlist}")

    # Load existing wordlist; preserve column order.
    with args.wordlist.open(encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        fieldnames = list(reader.fieldnames or [])
        rows = list(reader)
    required = {"word", "language", "length", "frequency", "difficulty",
                "clue", "source", "source_license", "lemma"}
    missing = required - set(fieldnames)
    if missing:
        raise SystemExit(f"wordlist missing columns: {missing}")

    existing_words = {r["word"].strip().lower() for r in rows}
    print(f"existing wordlist rows: {len(rows)}")
    print(f"existing distinct words: {len(existing_words)}")

    # Parse grammalecte at the requested length range.
    surfaces = parse_grammalecte(
        args.lexique, args.length_min, args.length_max, args.min_freq,
    )
    print(f"grammalecte surfaces L{args.length_min}-L{args.length_max} "
          f"freq>={args.min_freq}: {len(surfaces)}")

    # New rows = surfaces not already in the wordlist.
    new_rows: list[dict] = []
    for surface in sorted(surfaces):
        if surface.lower() in existing_words:
            continue
        lemma, freq = surfaces[surface]
        row = {col: "" for col in fieldnames}
        row["word"] = surface
        row["language"] = args.language
        row["length"] = str(len(surface))
        row["frequency"] = str(freq)
        # difficulty intentionally empty (see header docstring).
        # clue == word is the placeholder convention; the runtime loader
        # keeps any non-blank clue, and the merge_clues_into_wordlist.py
        # downstream step can replace this with an authored clue later.
        row["clue"] = surface
        row["source"] = args.source
        row["source_license"] = args.source_license
        row["lemma"] = lemma
        new_rows.append(row)

    print(f"new rows to append: {len(new_rows)}")
    if not new_rows:
        print("nothing to do")
        return
    if args.dry_run:
        print("--dry-run: not writing")
        return

    # Append in place. Keep column order; existing rows untouched.
    with args.wordlist.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, "") for k in fieldnames})
        for r in new_rows:
            w.writerow({k: r.get(k, "") for k in fieldnames})

    print(f"wrote {args.wordlist} (+{len(new_rows)} rows)")


if __name__ == "__main__":
    main()
