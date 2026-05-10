#!/usr/bin/env python3
"""Identify PP-productive verbs from words-fr.csv.

A verb is "PP-productive" when at least one of its inflected past-participle
surfaces appears in the runtime word list. The DPO corpus for iter13
(see docs/superpowers/specs/2026-05-05-pp-form-corpus-reframing-design.md)
draws its preference pairs from these verbs — they're the ones whose
PP-as-adjective surfaces currently ship as pp-only-skipped placeholders.

Usage:
    python scripts/eval/find_pp_productive_verbs.py \\
        --words grid/api/src/main/resources/words/words-fr.csv \\
        --lexique ~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt \\
        --output data/eval/pp_productive_verbs.csv \\
        --min-pp-freq 0
"""

from __future__ import annotations

import argparse
import csv
import os
import sys
from pathlib import Path

from morphology_index import MorphologyIndex


PP_TAGS = {"ppas"}
ALPHABETIC_OK = {"-", "'"}


def is_alphabetic(word: str) -> bool:
    return all(c.isalpha() or c in ALPHABETIC_OK for c in word)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--words", type=Path, required=True)
    p.add_argument("--lexique", type=Path, default=None)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--min-pp-freq", type=int, default=0,
                   help="minimum surface frequency to count as PP-productive")
    p.add_argument("--length-min", type=int, default=4)
    p.add_argument("--length-max", type=int, default=15)
    args = p.parse_args()

    lex = args.lexique or Path(os.path.expanduser(
        "~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt"))
    if not lex.exists():
        print(f"missing lexique: {lex}", file=sys.stderr)
        return 1

    print(f"loading morphology from {lex}...", file=sys.stderr)
    idx = MorphologyIndex.load(lex)

    # verb_lemma -> list[(pp_surface, frequency, gender, number)]
    pp_surfaces: dict[str, list[tuple[str, int, str, str]]] = {}

    with args.words.open(encoding="utf-8") as fh:
        rdr = csv.DictReader(fh)
        for row in rdr:
            word = row["word"]
            if row["language"] != "fr":
                continue
            try:
                length = int(row["length"])
            except (KeyError, ValueError):
                continue
            if not (args.length_min <= length <= args.length_max):
                continue
            if not is_alphabetic(word):
                continue

            try:
                freq = int(row.get("frequency") or 0)
            except ValueError:
                freq = 0

            for lemma, tags in idx.lookup_form(word):
                if not (tags & PP_TAGS):
                    continue
                # Verb past participles only; reject lemma == surface (the
                # masc-sg PP that happens to be the lemma for some verbs).
                if lemma == word.lower():
                    continue
                # Determine gender / number for reporting.
                gender = next((g for g in ("fem", "mas", "epi") if g in tags), "?")
                number = next((n for n in ("sg", "pl", "inv") if n in tags), "?")
                pp_surfaces.setdefault(lemma, []).append((word, freq, gender, number))

    rows: list[tuple[str, int, str, int, int]] = []
    for lemma, occurrences in pp_surfaces.items():
        kept = [o for o in occurrences if o[1] >= args.min_pp_freq]
        if not kept:
            continue
        # Top surface by frequency.
        top = max(kept, key=lambda o: o[1])
        total_freq = sum(o[1] for o in kept)
        rows.append((lemma, len(kept), top[0], top[1], total_freq))

    rows.sort(key=lambda r: r[4], reverse=True)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["lemma", "n_pp_surfaces", "top_pp_surface",
                    "top_pp_freq", "total_pp_freq"])
        w.writerows(rows)

    print(f"wrote {len(rows)} PP-productive verbs to {args.output}",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
