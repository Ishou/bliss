#!/usr/bin/env python3
"""Print N rows per length bucket from a lemma_clues CSV for hand-rating.

Useful when the full output is large and we want a quick uniform sample.
"""

import argparse
import csv
import random
from collections import defaultdict
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--per-length", type=int, default=10)
    parser.add_argument("--seed", type=int, default=20260503)
    args = parser.parse_args()

    with args.input.open(encoding="utf-8") as f:
        rows = list(csv.DictReader(f))

    by_len: dict[int, list[dict[str, str]]] = defaultdict(list)
    for r in rows:
        lemma = r.get("lemma") or r.get("word") or ""
        if not lemma:
            continue
        by_len[len(lemma)].append(r)

    rng = random.Random(args.seed)
    chosen: list[dict[str, str]] = []
    for length in sorted(by_len):
        bucket = by_len[length]
        n = min(args.per_length, len(bucket))
        chosen.extend(rng.sample(bucket, n))

    print(f"# {len(chosen)} rows from {args.input.name} ({args.per_length} per length)")
    print(f"# columns: idx | length | lemma (pos) -> clue [flag]")
    for i, r in enumerate(chosen, 1):
        lemma = r.get("lemma") or r.get("word") or ""
        flag = r.get("validation_flag", "")
        flag_s = "" if not flag or flag == "ok" else f" [{flag}]"
        print(f"{i:3d}  L{len(lemma):2d}  {lemma:24s} ({r.get('pos','?'):20s}) -> {r.get('lemma_clue', r.get('generated_clue', ''))!r}{flag_s}")


if __name__ == "__main__":
    main()
