#!/usr/bin/env python3
"""Mine DPO (chosen, rejected) pairs from hand-rated clue iterations.

Walks every `data/eval/lemma_clues_iter*.csv` row with a non-empty
rating. For each lemma, generates all ordered preference pairs where
the chosen clue has a strictly higher rating than the rejected one.

Rating order: y (1.0) > b (0.5) > n (0.0).

Output JSONL per line:
  {"prompt": "...", "chosen": "<clue>", "rejected": "<clue>"}

The prompt format matches build_corpus.py's render_user (short).

Pair tiers (strongest signal first):
  (y, n) — clear preference, most informative
  (y, b) — chosen is clear, rejected is borderline
  (b, n) — both imperfect but chosen is closer

Usage:
    python scripts/clue_generation/mine_dpo_pairs.py
"""

from __future__ import annotations

import csv
import json
import random
from collections import defaultdict
from pathlib import Path


REPO = Path(__file__).resolve().parent.parent.parent
OUT = REPO / "data" / "lora" / "dpo_pairs.jsonl"
SEED = 20260504

RATING_RANK = {"y": 2, "b": 1, "n": 0}


def render_user(lemma: str, pos: str) -> str:
    pos = (pos or "").strip()
    if pos:
        return f"Génère une définition mots-fléchés courte pour: {lemma.upper()} [{pos}]"
    return f"Génère une définition mots-fléchés courte pour: {lemma.upper()}"


def main() -> None:
    # Collect (rating, lemma, clue, pos, source) across all rated iters
    rows: list[tuple[str, str, str, str, str]] = []
    for path in sorted((REPO / "data" / "eval").glob("lemma_clues_iter*.csv")):
        with path.open(encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                rating = (r.get("rating") or "").strip().lower()
                if rating not in RATING_RANK:
                    continue
                lemma = (r.get("lemma") or "").strip().lower()
                clue = (r.get("lemma_clue") or "").strip()
                if not lemma or not clue:
                    continue
                rows.append((rating, lemma, clue, (r.get("pos") or "").strip(), path.stem))

    print(f"rated rows total: {len(rows)}")
    by_rating = defaultdict(int)
    for r in rows:
        by_rating[r[0]] += 1
    print(f"  by rating: {dict(by_rating)}")

    # Group by lemma
    by_lemma: dict[str, list[tuple[str, str, str, str]]] = defaultdict(list)
    for rating, lemma, clue, pos, src in rows:
        by_lemma[lemma].append((rating, clue, pos, src))

    # Generate ordered preference pairs (chosen-rating > rejected-rating)
    pairs: list[dict[str, str]] = []
    pair_tier_counts = defaultdict(int)
    for lemma, items in by_lemma.items():
        # Dedup on clue text to avoid same-clue pairs
        seen_clues: dict[str, str] = {}
        for rating, clue, pos, _src in items:
            key = clue.lower()
            # Keep the best rating per unique clue
            if key not in seen_clues or RATING_RANK[rating] > RATING_RANK[seen_clues[key][0]]:
                seen_clues[key] = (rating, clue, pos)
        clues = list(seen_clues.values())
        if len(clues) < 2:
            continue
        for i, (r1, c1, p1) in enumerate(clues):
            for j, (r2, c2, _p2) in enumerate(clues):
                if i == j:
                    continue
                if RATING_RANK[r1] <= RATING_RANK[r2]:
                    continue
                tier = f"({r1},{r2})"
                pair_tier_counts[tier] += 1
                pairs.append({
                    "prompt": render_user(lemma, p1),
                    "chosen": c1,
                    "rejected": c2,
                })

    print(f"\nDPO pairs generated: {len(pairs)}")
    print(f"  by tier: {dict(pair_tier_counts)}")

    # Shuffle so tiers interleave (DPO trainers like balanced batches)
    rng = random.Random(SEED)
    rng.shuffle(pairs)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8") as f:
        for p in pairs:
            f.write(json.dumps(p, ensure_ascii=False) + "\n")
    print(f"\nwrote {OUT}")

    # Print 5 sample pairs (varied tiers if possible)
    print("\nSample pairs:")
    for p in pairs[:5]:
        print(f"  prompt:   {p['prompt']}")
        print(f"  chosen:   {p['chosen']}")
        print(f"  rejected: {p['rejected']}")
        print()


if __name__ == "__main__":
    main()
