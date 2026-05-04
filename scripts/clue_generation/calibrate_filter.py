#!/usr/bin/env python3
"""Phase 2 calibration: fine-tune the DBnary-pretrained filter on
hand-rated clue rows.

Phase 1 (train_filter.py) teaches lemma↔concept semantic matching from
613k DBnary pairs. That alone accepts verbose dictionary-style text
because it's semantically valid. This phase uses 554 hand-rated rows
to calibrate "is this a good *clue*?" specifically — penalizing
verbose, derivative, or stylistically-off candidates that are
semantically correct.

Loss: cosine-similarity regression toward target scores
    y → 1.0  (chosen / strong positive)
    b → 0.5  (borderline / weak positive)
    n → 0.0  (rejected)

Output: models/filter-camembert-v2/ — drop-in replacement for v1.

Usage:
    python scripts/clue_generation/calibrate_filter.py
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
PHASE2 = REPO / "models" / "filter-camembert-v2"
SEED = 20260504

RATING_TO_SCORE = {"y": 1.0, "b": 0.5, "n": 0.0}


def main() -> None:
    from sentence_transformers import (
        InputExample, SentenceTransformer, losses, evaluation,
    )
    from torch.utils.data import DataLoader

    if not PHASE1.exists():
        sys.exit(f"missing Phase 1 model at {PHASE1}; run train_filter.py first")

    # 1. Build (lemma, candidate, score) triples from ALL rated iter rows.
    # Eval lemmas held out by build_filter_corpus.py — load them to exclude.
    held_out: set[str] = set()
    eval_rows_seen = []
    with (DATA / "eval_human.jsonl").open(encoding="utf-8") as f:
        for line in f:
            obj = json.loads(line)
            held_out.add(obj["lemma"])
            eval_rows_seen.append(obj)

    rows: list[tuple[str, str, float]] = []
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
                    continue   # do NOT train on held-out lemmas
                clue = (r.get("lemma_clue") or "").strip()
                if not lemma or not clue:
                    continue
                rows.append((lemma, clue, RATING_TO_SCORE[rating]))

    rng = random.Random(SEED)
    rng.shuffle(rows)
    n_valid = max(20, int(len(rows) * 0.1))
    valid = rows[:n_valid]
    train = rows[n_valid:]
    print(f"calibration: train={len(train)} valid={len(valid)} (held-out lemmas excluded)")

    # 2. Load Phase 1 model
    print(f"loading Phase 1 model from {PHASE1}...", file=sys.stderr)
    model = SentenceTransformer(str(PHASE1))

    # 3. Continue training with cosine-similarity regression
    train_examples = [InputExample(texts=[l, c], label=s) for l, c, s in train]
    train_loader = DataLoader(train_examples, shuffle=True, batch_size=8)
    train_loss = losses.CosineSimilarityLoss(model)

    # 4. Validation: cosine sim should track regression target
    valid_evaluator = evaluation.EmbeddingSimilarityEvaluator(
        sentences1=[l for l, _, _ in valid],
        sentences2=[c for _, c, _ in valid],
        scores=[s for _, _, s in valid],
        name="calib-valid",
    )

    PHASE2.mkdir(parents=True, exist_ok=True)
    model.fit(
        train_objectives=[(train_loader, train_loss)],
        evaluator=valid_evaluator,
        evaluation_steps=20,
        epochs=4,                  # small dataset; multiple passes
        warmup_steps=10,
        output_path=str(PHASE2),
        show_progress_bar=True,
        save_best_model=True,
    )
    print(f"\nsaved to {PHASE2}")
    print("next: scripts/clue_generation/eval_filter.py "
          "(point MODEL_PATH at the v2 directory)")


if __name__ == "__main__":
    main()
