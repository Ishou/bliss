#!/usr/bin/env python3
"""Filter v4: continue v2 training with triplet loss on (y, b) and (y, n)
preference pairs. Goal: fix the y < b score inversion.

v2 distribution by rating:
  y mean=0.745  b mean=0.784 (← HIGHER!)  n mean=0.683

The b > y inversion comes from b clues being verbose dictionary-style,
which the contrastive DBnary pretraining rewarded. Triplet loss with
explicit (lemma, y, b) ordering teaches the model concise > verbose.

Triplets are mined from any pair of rated clues for the same lemma
(when one is y and another is b/n). Plus cross-lemma fallback when
same-lemma pairs are scarce.

Output: models/filter-camembert-v4/
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
OUT = REPO / "models" / "filter-camembert-v4"
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

    # Collect all rated rows (excluding held-out lemmas)
    by_lemma: dict[str, list[tuple[str, str]]] = defaultdict(list)  # lemma -> [(rating, clue)]
    for path in sorted((REPO / "data" / "eval").glob("lemma_clues_iter*.csv")):
        if not path.name.startswith("lemma_clues_iter"):
            continue
        with path.open(encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                rating = (r.get("rating") or "").strip().lower()
                if rating not in ("y", "b", "n"):
                    continue
                lemma = (r.get("lemma") or "").strip().lower()
                if lemma in held_out:
                    continue
                clue = (r.get("lemma_clue") or "").strip()
                if not lemma or not clue:
                    continue
                by_lemma[lemma].append((rating, clue))

    # Mine same-lemma triplets: anchor=lemma, positive=y_clue, negative=b_or_n_clue
    same_lemma_triplets: list[tuple[str, str, str]] = []
    for lemma, items in by_lemma.items():
        # dedup on clue text first
        seen: set[str] = set()
        uniq = []
        for rating, clue in items:
            if clue.lower() in seen:
                continue
            seen.add(clue.lower())
            uniq.append((rating, clue))
        ys = [c for r, c in uniq if r == "y"]
        bs = [c for r, c in uniq if r == "b"]
        ns = [c for r, c in uniq if r == "n"]
        for y in ys:
            for b in bs:
                same_lemma_triplets.append((lemma, y, b))
            for n in ns:
                same_lemma_triplets.append((lemma, y, n))

    print(f"same-lemma triplets: {len(same_lemma_triplets)}", file=sys.stderr)

    # Cross-lemma triplets: anchor=lemmaA, positive=y_clue_for_A,
    # negative=any_clue_for_B (different lemma). This teaches the model
    # to pull lemma close to its OWN clues over other lemmas' clues.
    rng = random.Random(SEED)
    all_y = [(l, c) for l, items in by_lemma.items() for r, c in items if r == "y"]
    all_clues = [(l, c) for l, items in by_lemma.items() for _, c in items]
    cross_lemma_triplets: list[tuple[str, str, str]] = []
    for lemma, y_clue in all_y:
        # pick a random clue from a different lemma
        for _ in range(3):
            other_lemma, other_clue = rng.choice(all_clues)
            if other_lemma != lemma:
                cross_lemma_triplets.append((lemma, y_clue, other_clue))
                break

    print(f"cross-lemma triplets: {len(cross_lemma_triplets)}", file=sys.stderr)

    triplets = same_lemma_triplets + cross_lemma_triplets
    rng.shuffle(triplets)

    # 90/10 train/valid
    n_valid = max(20, int(len(triplets) * 0.1))
    valid = triplets[:n_valid]
    train = triplets[n_valid:]
    print(f"train={len(train)} valid={len(valid)}", file=sys.stderr)

    print(f"loading v2 from {V2}...", file=sys.stderr)
    model = SentenceTransformer(str(V2))

    train_examples = [InputExample(texts=[a, p, n]) for a, p, n in train]
    train_loader = DataLoader(train_examples, shuffle=True, batch_size=16)
    train_loss = losses.TripletLoss(model, triplet_margin=0.3)

    valid_evaluator = evaluation.TripletEvaluator(
        anchors=[t[0] for t in valid],
        positives=[t[1] for t in valid],
        negatives=[t[2] for t in valid],
        name="v4-valid",
    )

    OUT.mkdir(parents=True, exist_ok=True)
    model.fit(
        train_objectives=[(train_loader, train_loss)],
        evaluator=valid_evaluator,
        evaluation_steps=50,
        epochs=3,
        warmup_steps=50,
        output_path=str(OUT),
        show_progress_bar=True,
        save_best_model=True,
    )
    print(f"\nsaved to {OUT}")


if __name__ == "__main__":
    main()
