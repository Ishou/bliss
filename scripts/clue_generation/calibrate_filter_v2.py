#!/usr/bin/env python3
"""Phase 2 v2: calibrate the DBnary-pretrained filter with EXPANDED
rated corpus (iter rated + synthetic Claude-rated y/n pairs).

Same architecture as calibrate_filter.py, more data:
- 400 synthetic y (data/lora/synthetic_rated.csv)
- 237 synthetic n  (data/lora/synthetic_rated.csv)
- ~308 iter rated (held-out lemmas excluded)
Total ≈ 945 rated rows vs original 308.

Output: models/filter-camembert-v3/
"""

from __future__ import annotations

import csv
import json
import random
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
DATA = REPO / "data" / "lora_filter"
PHASE1 = REPO / "models" / "filter-camembert-v1"
OUT = REPO / "models" / "filter-camembert-v3"
SEED = 20260504

RATING_TO_SCORE = {"y": 1.0, "b": 0.5, "n": 0.0}


def main() -> None:
    from sentence_transformers import (
        InputExample, SentenceTransformer, losses, evaluation,
    )
    from torch.utils.data import DataLoader

    held_out: set[str] = set()
    with (DATA / "eval_human.jsonl").open(encoding="utf-8") as f:
        for line in f:
            held_out.add(json.loads(line)["lemma"])

    rows: list[tuple[str, str, float]] = []

    # Iter rated rows
    iter_count = 0
    for path in sorted((REPO / "data" / "eval").glob("lemma_clues_iter*.csv")):
        if not path.name.startswith("lemma_clues_iter"):
            continue
        with path.open(encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                rating = (r.get("rating") or "").strip().lower()
                if rating not in RATING_TO_SCORE:
                    continue
                lemma = (r.get("lemma") or "").strip().lower()
                if lemma in held_out:
                    continue
                clue = (r.get("lemma_clue") or "").strip()
                if not lemma or not clue:
                    continue
                rows.append((lemma, clue, RATING_TO_SCORE[rating]))
                iter_count += 1

    # Synthetic Claude-rated rows
    synth_count = 0
    synth = REPO / "data" / "lora" / "synthetic_rated.csv"
    if synth.exists():
        with synth.open(encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                lemma = (r.get("lemma") or "").strip().lower()
                if lemma in held_out:
                    continue
                clue = (r.get("lemma_clue") or "").strip()
                rating = (r.get("rating") or "").strip().lower()
                if rating not in RATING_TO_SCORE or not lemma or not clue:
                    continue
                rows.append((lemma, clue, RATING_TO_SCORE[rating]))
                synth_count += 1

    print(f"iter rated: {iter_count}  synthetic rated: {synth_count}")
    print(f"total: {len(rows)} (held-out lemmas excluded)")

    rng = random.Random(SEED)
    rng.shuffle(rows)
    n_valid = max(40, int(len(rows) * 0.1))
    valid = rows[:n_valid]
    train = rows[n_valid:]
    print(f"train={len(train)} valid={len(valid)}")

    print(f"loading Phase 1 model from {PHASE1}...", file=sys.stderr)
    model = SentenceTransformer(str(PHASE1))

    train_examples = [InputExample(texts=[l, c], label=s) for l, c, s in train]
    train_loader = DataLoader(train_examples, shuffle=True, batch_size=16)
    train_loss = losses.CosineSimilarityLoss(model)
    valid_evaluator = evaluation.EmbeddingSimilarityEvaluator(
        sentences1=[l for l, _, _ in valid],
        sentences2=[c for _, c, _ in valid],
        scores=[s for _, _, s in valid],
        name="calib2-valid",
    )

    OUT.mkdir(parents=True, exist_ok=True)
    model.fit(
        train_objectives=[(train_loader, train_loss)],
        evaluator=valid_evaluator,
        evaluation_steps=30,
        epochs=4,
        warmup_steps=20,
        output_path=str(OUT),
        show_progress_bar=True,
        save_best_model=True,
    )
    print(f"\nsaved to {OUT}")


if __name__ == "__main__":
    main()
