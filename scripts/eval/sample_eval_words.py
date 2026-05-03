#!/usr/bin/env python3
"""Build data/eval/sample_100.csv: a stratified 100-word sample of the French
lexicon for the Phase 0 clue-generation eval.

Stratification: 10 words per length bucket, lengths 3..12. Filtered to entries
whose `lemma` is non-empty and whose `word` is purely alphabetic (excludes
Roman numerals, chemical symbols, abbreviations).

Note: words-fr.csv does not retain POS. The plan called for POS stratification
but the data layer makes that infeasible without re-running grammalecte. Length
stratification + lemmatized-alphabetic filter is the proxy used here.
"""

import csv
import random
from pathlib import Path

LENGTHS = list(range(3, 13))
PER_BUCKET = 10
SEED = 20260503


def main() -> None:
    repo_root = Path(__file__).resolve().parent.parent.parent
    src = repo_root / "grid" / "api" / "src" / "main" / "resources" / "words" / "words-fr.csv"
    out = repo_root / "data" / "eval" / "sample_100.csv"
    out.parent.mkdir(parents=True, exist_ok=True)

    buckets: dict[int, list[dict[str, str]]] = {n: [] for n in LENGTHS}
    with src.open(encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            try:
                length = int(row["length"])
            except ValueError:
                continue
            if length not in buckets:
                continue
            if not row.get("lemma"):
                continue
            if not row["word"].isalpha():
                continue
            buckets[length].append(row)

    rng = random.Random(SEED)
    chosen: list[dict[str, str]] = []
    for length in LENGTHS:
        pool = buckets[length]
        if len(pool) < PER_BUCKET:
            raise RuntimeError(f"length {length}: only {len(pool)} candidates, need {PER_BUCKET}")
        chosen.extend(rng.sample(pool, PER_BUCKET))

    fieldnames = ["word", "language", "length", "frequency", "lemma"]
    with out.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        for row in chosen:
            writer.writerow({k: row[k] for k in fieldnames})

    print(f"Wrote {len(chosen)} entries to {out.relative_to(repo_root)}")


if __name__ == "__main__":
    main()
