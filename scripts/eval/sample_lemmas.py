#!/usr/bin/env python3
"""Sample N unique lemmas from words-fr.csv, stratified by lemma length.

For stress-testing the clue-generation pipeline at scale (Phase 3 §C).
Output is a CSV with columns: word, language, length, frequency, lemma.
The `word` column equals `lemma` so downstream scripts (fetch_dbnary,
enrich_with_morphology, generate_clues_lemma) treat it as the citation form.
"""

import argparse
import csv
import random
from collections import defaultdict
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--n", type=int, default=5000, help="(stratified-random mode) number of lemmas")
    parser.add_argument(
        "--top-per-length",
        type=int,
        default=None,
        help="alt mode: pick top-N lemmas by frequency per length bucket",
    )
    parser.add_argument(
        "--random-from-top",
        type=int,
        default=None,
        help="alt mode: at each length, take top --top-pool by frequency, then random-sample N from that pool",
    )
    parser.add_argument(
        "--top-pool",
        type=int,
        default=1000,
        help="size of the per-length top pool when using --random-from-top (default 1000)",
    )
    parser.add_argument(
        "--min-length",
        type=int,
        default=2,
        help="minimum lemma length to include (default 2; useful as 4 to skip 2/3-letter abbreviations)",
    )
    parser.add_argument(
        "--max-length",
        type=int,
        default=15,
        help="maximum lemma length (default 15)",
    )
    parser.add_argument("--seed", type=int, default=20260503)
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument(
        "--source",
        type=Path,
        default=None,
        help="path to words-<lang>.csv (default: grid/api/src/main/resources/words/words-fr.csv)",
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent.parent
    src = args.source or repo_root / "grid" / "api" / "src" / "main" / "resources" / "words" / "words-fr.csv"
    out = args.output or repo_root / "data" / "eval" / f"sample_{args.n}.csv"
    out.parent.mkdir(parents=True, exist_ok=True)

    if not src.exists():
        raise SystemExit(f"missing {src}")

    # Pull one canonical row per (language, lemma) — preferring the row whose
    # word IS the lemma (citation form) when available, else the first one.
    by_lemma: dict[tuple[str, str], dict[str, str]] = {}
    with src.open(encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            lemma = row.get("lemma") or row["word"]
            if not lemma or not row["word"].isalpha():
                continue
            key = (row["language"], lemma)
            existing = by_lemma.get(key)
            if existing is None:
                by_lemma[key] = row
            elif existing["word"] != existing.get("lemma") and row["word"] == row.get("lemma"):
                # Replace a non-citation row with a citation one when we find it.
                by_lemma[key] = row

    print(f"unique lemmas in source: {len(by_lemma)}")
    if len(by_lemma) < args.n:
        raise SystemExit(f"only {len(by_lemma)} unique lemmas, asked for {args.n}")

    # Stratify by lemma length, weighted by the corpus distribution so the
    # sample mirrors the population. Bucket by length, then sample
    # proportionally.
    by_len: dict[int, list[dict[str, str]]] = defaultdict(list)
    for row in by_lemma.values():
        by_len[len(row.get("lemma") or row["word"])].append(row)

    chosen: list[dict[str, str]] = []
    if args.top_per_length is not None:
        # Top-N by frequency per length bucket; deterministic.
        for length in range(args.min_length, args.max_length + 1):
            bucket = by_len.get(length, [])
            ranked = sorted(
                bucket,
                key=lambda r: float(r.get("frequency") or 0),
                reverse=True,
            )
            chosen.extend(ranked[: args.top_per_length])
    elif args.random_from_top is not None:
        # At each length, take top --top-pool by freq, random-sample N from it.
        rng = random.Random(args.seed)
        for length in range(args.min_length, args.max_length + 1):
            bucket = by_len.get(length, [])
            ranked = sorted(
                bucket,
                key=lambda r: float(r.get("frequency") or 0),
                reverse=True,
            )
            pool = ranked[: args.top_pool]
            n = min(args.random_from_top, len(pool))
            chosen.extend(rng.sample(pool, n))
    else:
        rng = random.Random(args.seed)
        total = sum(len(v) for v in by_len.values())
        remainder: list[dict[str, str]] = []
        for length, rows in by_len.items():
            share = round(args.n * len(rows) / total)
            if share <= 0:
                remainder.extend(rows)
                continue
            if share > len(rows):
                chosen.extend(rows)
                continue
            picks = rng.sample(rows, share)
            chosen.extend(picks)
            remainder.extend(r for r in rows if r not in picks)
        if len(chosen) < args.n and remainder:
            chosen.extend(rng.sample(remainder, min(args.n - len(chosen), len(remainder))))
        chosen = chosen[: args.n]

    # Schema mirrors fetch_dbnary_for_sample.py's output so enrich +
    # generate can consume it directly. pos/definition/synonyms are empty
    # — the stress run skips DBnary fetch.
    fieldnames = ["word", "language", "length", "frequency", "lemma", "pos", "definition", "synonyms"]
    with out.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        for r in chosen:
            lemma = r.get("lemma") or r["word"]
            writer.writerow({
                "word": lemma,
                "language": r["language"],
                "length": len(lemma),
                "frequency": r.get("frequency", ""),
                "lemma": lemma,
                "pos": "",
                "definition": "",
                "synonyms": "",
            })

    try:
        out_disp = out.resolve().relative_to(repo_root)
    except ValueError:
        out_disp = out
    print(f"Wrote {len(chosen)} lemmas to {out_disp}")


if __name__ == "__main__":
    main()
