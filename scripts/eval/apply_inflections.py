#!/usr/bin/env python3
"""Cross lemma_clues.csv x sample_100_enriched.csv: for every surface form,
look up its lemma's clue and inflect it to match the surface morphology via
grammalecte's lemma->form table.

Reads:
  data/eval/sample_100_enriched.csv   (must have `tags` column from morphology enrichment)
  data/eval/lemma_clues.csv

Writes:
  data/eval/run_v0_lemma_results.csv  (per-surface, ready for hand-rating)

Columns in the output:
  word, length, lemma, pos, morphology, definition, synonyms,
  lemma_clue, generated_clue, inflection_flag, rating

`inflection_flag` is one of '' / head-pos-mismatch / no-target-pos / no-head /
no-inflection / identity / empty. Rate-first targets are rows where
generated_clue diverges from lemma_clue and the flag is empty.
"""

import argparse
import csv
import sys
from pathlib import Path

from inflect_clue import inflect_clue
from morphology_index import MorphologyIndex


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lexique", type=Path, required=True, help="grammalecte lexique path")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent.parent
    enriched = repo_root / "data" / "eval" / "sample_100_enriched.csv"
    lemma_clues_path = repo_root / "data" / "eval" / "lemma_clues.csv"
    out = repo_root / "data" / "eval" / "run_v0_lemma_results.csv"

    if not enriched.exists() or not lemma_clues_path.exists():
        print("missing inputs (run enrich_with_morphology.py + generate_clues_lemma.py first)", file=sys.stderr)
        sys.exit(1)

    print(f"loading morphology index from {args.lexique}...", file=sys.stderr)
    index = MorphologyIndex.load(args.lexique)

    with enriched.open(encoding="utf-8", newline="") as f:
        sample_rows = list(csv.DictReader(f))
    with lemma_clues_path.open(encoding="utf-8", newline="") as f:
        lemma_rows = {r["lemma"]: r for r in csv.DictReader(f)}

    fieldnames = [
        "word", "length", "lemma", "pos", "morphology",
        "definition", "synonyms",
        "lemma_clue", "generated_clue", "inflection_flag", "rating",
    ]
    out_rows: list[dict[str, str]] = []
    flag_counts: dict[str, int] = {}
    for row in sample_rows:
        lemma = (row.get("lemma") or row["word"]).strip().lower()
        lemma_clue = lemma_rows.get(lemma, {}).get("lemma_clue", "")
        surface_tags = set((row.get("tags") or "").split())
        if not lemma_clue:
            inflected = ""
            flag = "no-lemma-clue"
        else:
            r = inflect_clue(lemma_clue, surface_tags, index)
            inflected = r.text
            flag = r.flag
        flag_counts[flag or "ok"] = flag_counts.get(flag or "ok", 0) + 1
        out_rows.append({
            "word": row["word"],
            "length": row.get("length", ""),
            "lemma": lemma,
            "pos": row.get("pos", ""),
            "morphology": row.get("morphology", ""),
            "definition": row.get("definition", ""),
            "synonyms": row.get("synonyms", ""),
            "lemma_clue": lemma_clue,
            "generated_clue": inflected,
            "inflection_flag": flag,
            "rating": "",
        })

    with out.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        for r in out_rows:
            writer.writerow({k: r.get(k, "") for k in fieldnames})

    print(f"\nWrote {len(out_rows)} rows to {out.relative_to(repo_root)}")
    for flag, n in sorted(flag_counts.items(), key=lambda kv: -kv[1]):
        print(f"  {flag:20s} {n}")
    print("\nHand-rate the `rating` column then run: python scripts/eval/eval_clue_quality.py")


if __name__ == "__main__":
    main()
