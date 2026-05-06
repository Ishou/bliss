#!/usr/bin/env python3
"""Diff two iter eval CSVs verb-by-verb. Used to see what shifted after a
theme-targeted DPO addition.

Usage:
    python scripts/eval/diff_iter_evals.py \\
        --before data/eval/iter13_pp_eval.csv \\
        --after  data/eval/iter13_1_pp_eval.csv
"""
from __future__ import annotations

import argparse
import csv
from pathlib import Path


def load(path: Path) -> dict[str, dict]:
    return {r["lemma"]: r for r in csv.DictReader(path.open(encoding="utf-8"))}


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--before", type=Path, required=True)
    p.add_argument("--after", type=Path, required=True)
    args = p.parse_args()

    a = load(args.before)
    b = load(args.after)

    common = sorted(set(a) & set(b))
    flipped: list[tuple[str, dict, dict]] = []
    same: list[tuple[str, dict]] = []
    for lemma in common:
        ba = a[lemma]
        bb = b[lemma]
        if (ba["lemma_clue"] != bb["lemma_clue"]
                or ba["validation_flag"] != bb["validation_flag"]
                or ba["inflection_status"] != bb["inflection_status"]):
            flipped.append((lemma, ba, bb))
        else:
            same.append((lemma, ba))

    print(f"flipped: {len(flipped)} / {len(common)}")
    print(f"same:    {len(same)} / {len(common)}")
    print()
    print("=== flipped rows ===")
    for lemma, ba, bb in flipped:
        before = (f'{ba["lemma_clue"]:35s} | '
                  f'flag={ba["validation_flag"]:14s} '
                  f'infl={ba["inflection_status"]}')
        after = (f'{bb["lemma_clue"]:35s} | '
                 f'flag={bb["validation_flag"]:14s} '
                 f'infl={bb["inflection_status"]}')
        print(f"{lemma}")
        print(f"  before: {before}")
        print(f"  after:  {after}")
    return 0


if __name__ == "__main__":
    import sys
    sys.exit(main())
