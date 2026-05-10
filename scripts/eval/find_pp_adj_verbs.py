#!/usr/bin/env python3
"""Refined PP-productive verb detection: filter to verbs whose PP surfaces
are CO-TAGGED as adjective in grammalecte.

Rationale: `find_pp_productive_verbs.py` flags any verb with a `ppas` surface
in words-fr.csv. But many intransitive verbs (déroger, cesser, échapper)
have PP forms used only in compound tenses (`a dérogé à`), not as standalone
adjectives. Cluing these surfaces as PP-as-adj produces awkward output.

A surface tagged BOTH `{ppas, ...}` AND `{adj, ...}` (or where grammalecte
includes the adj POS class for the same form) is genuinely PP-adjectivally
productive — `levée`, `forée`, `accordée`, `aggravée`. Those are the eval
targets.

Usage:
    python scripts/eval/find_pp_adj_verbs.py \\
        --words grid/api/src/main/resources/words/words-fr.csv \\
        --output data/eval/pp_adj_verbs.csv
"""
from __future__ import annotations

import argparse
import csv
import os
import sys
from pathlib import Path

from morphology_index import MorphologyIndex


ALPHA_OK = {"-", "'"}


def is_alpha(word: str) -> bool:
    return all(c.isalpha() or c in ALPHA_OK for c in word)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--words", type=Path, required=True)
    p.add_argument("--lexique", type=Path, default=None)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--length-min", type=int, default=4)
    p.add_argument("--length-max", type=int, default=15)
    args = p.parse_args()

    lex = args.lexique or Path(os.path.expanduser(
        "~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt"))
    print(f"loading morphology…", file=sys.stderr)
    idx = MorphologyIndex.load(lex)

    # verb_lemma -> set of (pp_surface, freq) where surface is PP+adj co-tagged
    pp_adj: dict[str, list[tuple[str, int]]] = {}

    with args.words.open(encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            if row.get("language") != "fr":
                continue
            try:
                length = int(row["length"])
            except (KeyError, ValueError):
                continue
            if not (args.length_min <= length <= args.length_max):
                continue
            word = row["word"]
            if not is_alpha(word):
                continue
            try:
                freq = int(row.get("frequency") or 0)
            except ValueError:
                freq = 0

            # For each (lemma, tags) analysis of this surface, check for
            # the PP+adj co-tagging signal: there must be at least one
            # analysis with `ppas` in tags AND at least one (possibly
            # different) analysis with `adj` in tags. The same form
            # appearing in BOTH classes is grammalecte's signal for
            # PP-adjectivally-productive.
            analyses = idx.lookup_form(word)
            has_ppas_with_verb_lemma = False
            has_adj = False
            verb_lemma = None
            for lemma, tags in analyses:
                if "ppas" in tags:
                    has_ppas_with_verb_lemma = True
                    verb_lemma = lemma
                if "adj" in tags:
                    has_adj = True
            if has_ppas_with_verb_lemma and has_adj and verb_lemma:
                if verb_lemma == word.lower():
                    continue  # surface == lemma; not interesting
                pp_adj.setdefault(verb_lemma, []).append((word, freq))

    rows: list[tuple[str, int, str, int, int]] = []
    for lemma, occurrences in pp_adj.items():
        top = max(occurrences, key=lambda o: o[1])
        total_freq = sum(o[1] for o in occurrences)
        rows.append((lemma, len(occurrences), top[0], top[1], total_freq))
    rows.sort(key=lambda r: r[4], reverse=True)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["lemma", "n_pp_adj_surfaces", "top_pp_surface",
                    "top_pp_freq", "total_pp_freq"])
        w.writerows(rows)

    print(f"wrote {len(rows)} PP-adjectivally-productive verbs to {args.output}",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
