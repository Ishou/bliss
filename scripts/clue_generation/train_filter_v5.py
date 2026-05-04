#!/usr/bin/env python3
"""Filter v5: continue v2 with triplet loss using ROUND-2 hand-authored
paired (y, n) data + existing iter rated rows.

Key change vs v4: round2 has 436 paired (y, n) for SAME lemma —
direct anchor↔positive↔negative triplets with built-in style contrast.
Plus the n's are SUBTLE wrong (adjacent-sense, polysemic-wrong) not
opposites — exactly the failure mode the filter needs to learn.

Output: models/filter-camembert-v5/
"""

from __future__ import annotations

import csv
import json
import random
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
DATA = REPO / "data" / "lora_filter"
V2 = REPO / "models" / "filter-camembert-v2"
OUT = REPO / "models" / "filter-camembert-v5"
SEED = 20260504


def main() -> None:
    from sentence_transformers import (
        InputExample, SentenceTransformer, losses, evaluation,
    )
    from torch.utils.data import DataLoader

    held_out: set[str] = set()
    with (DATA / "eval_human.jsonl").open(encoding="utf-8") as f:
        for line in f:
            held_out.add(json.loads(line)["lemma"])

    # 1. ROUND2 paired (y, n) — best signal, plus active-learning rated rows
    triplets: list[tuple[str, str, str]] = []
    by_lemma: dict[str, dict[str, str]] = defaultdict(dict)
    for src_path in [
        REPO / "data" / "lora" / "round2_rated.csv",
        REPO / "data" / "lora" / "active" / "all_rated.csv",
    ]:
        if not src_path.exists():
            continue
        with src_path.open(encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                lemma = (r.get("lemma") or "").strip().lower()
                if lemma in held_out: continue
                clue = (r.get("lemma_clue") or "").strip()
                rating = (r.get("rating") or "").strip().lower()
                if rating in ("y", "n") and lemma and clue:
                    # last source wins for duplicates
                    by_lemma[lemma][rating] = clue
    for lemma, items in by_lemma.items():
        if "y" in items and "n" in items:
            triplets.append((lemma, items["y"], items["n"]))
    print(f"round2+active same-lemma (y,n) triplets: {len(triplets)}", file=sys.stderr)

    # 2. Iter rated (y vs b/n) same-lemma pairs
    iter_by_lemma = defaultdict(list)
    for path in sorted((REPO / "data" / "eval").glob("lemma_clues_iter*.csv")):
        if not path.name.startswith("lemma_clues_iter"): continue
        with path.open(encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                rating = (r.get("rating") or "").strip().lower()
                if rating not in ("y", "b", "n"): continue
                lemma = (r.get("lemma") or "").strip().lower()
                if lemma in held_out: continue
                clue = (r.get("lemma_clue") or "").strip()
                if lemma and clue:
                    iter_by_lemma[lemma].append((rating, clue))
    iter_triplets = 0
    for lemma, items in iter_by_lemma.items():
        ys = [c for r, c in items if r == "y"]
        bns = [c for r, c in items if r in ("b", "n")]
        for y in ys:
            for bn in bns:
                triplets.append((lemma, y, bn))
                iter_triplets += 1
    print(f"iter same-lemma triplets added: {iter_triplets}", file=sys.stderr)

    # 3. Cross-lemma triplets for diversity
    rng = random.Random(SEED)
    all_y_pairs = []
    for lemma, items in by_lemma.items():
        if "y" in items:
            all_y_pairs.append((lemma, items["y"]))
    for lemma, items in iter_by_lemma.items():
        for r, c in items:
            if r == "y":
                all_y_pairs.append((lemma, c))

    cross_added = 0
    for lemma, y_clue in all_y_pairs:
        for _ in range(2):  # 2 cross-lemma negatives per anchor
            other_lemma, other_y = rng.choice(all_y_pairs)
            if other_lemma != lemma:
                triplets.append((lemma, y_clue, other_y))
                cross_added += 1
                break
    print(f"cross-lemma triplets added: {cross_added}", file=sys.stderr)

    rng.shuffle(triplets)
    n_valid = max(50, int(len(triplets) * 0.1))
    valid = triplets[:n_valid]
    train = triplets[n_valid:]
    print(f"\ntotal triplets: {len(triplets)}  train={len(train)} valid={len(valid)}",
          file=sys.stderr)

    print(f"\nloading v2 from {V2}...", file=sys.stderr)
    model = SentenceTransformer(str(V2))

    train_examples = [InputExample(texts=[a, p, n]) for a, p, n in train]
    train_loader = DataLoader(train_examples, shuffle=True, batch_size=16)
    train_loss = losses.TripletLoss(model, triplet_margin=0.3)

    valid_evaluator = evaluation.TripletEvaluator(
        anchors=[t[0] for t in valid], positives=[t[1] for t in valid],
        negatives=[t[2] for t in valid], name="v5-valid",
    )

    OUT.mkdir(parents=True, exist_ok=True)
    model.fit(
        train_objectives=[(train_loader, train_loss)],
        evaluator=valid_evaluator, evaluation_steps=100,
        epochs=4, warmup_steps=100,
        output_path=str(OUT), show_progress_bar=True, save_best_model=True,
    )
    print(f"\nsaved to {OUT}")


if __name__ == "__main__":
    main()
