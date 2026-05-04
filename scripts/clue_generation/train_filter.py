#!/usr/bin/env python3
"""Train the DBnary filter: a sentence-pair scorer that estimates how
well a candidate clue matches a target lemma.

Approach: fine-tune a French sentence encoder with contrastive loss
(MultipleNegativesRankingLoss). Each batch's other positives serve as
in-batch negatives — we don't need explicit negative pairs.

Eval at the end: load 554 human-rated rows, score each candidate, check
if filter score correlates with y/b/n rating.

Usage:
    python scripts/clue_generation/train_filter.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
DATA = REPO / "data" / "lora_filter"
MODEL_OUT = REPO / "models" / "filter-camembert-v1"

# Picked for: French-specific sentence-similarity, 110M params, fast
# on MPS, MIT license. Fallback to multilingual-MiniLM if unavailable.
BASE_MODEL = "dangvantuan/sentence-camembert-base"
FALLBACK = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"


def load_pairs(path: Path) -> list[tuple[str, str]]:
    pairs = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            obj = json.loads(line)
            pairs.append((obj["anchor"], obj["positive"]))
    return pairs


def main() -> None:
    from sentence_transformers import (
        InputExample, SentenceTransformer, losses, evaluation,
    )
    from torch.utils.data import DataLoader

    train = load_pairs(DATA / "train.jsonl")
    valid = load_pairs(DATA / "valid.jsonl")
    print(f"train: {len(train):,}  valid: {len(valid):,}", file=sys.stderr)

    print(f"loading base model {BASE_MODEL}...", file=sys.stderr)
    try:
        model = SentenceTransformer(BASE_MODEL)
    except Exception as e:
        print(f"  failed: {e}; falling back to {FALLBACK}", file=sys.stderr)
        model = SentenceTransformer(FALLBACK)

    train_examples = [InputExample(texts=[a, p]) for a, p in train]
    # MPS sweet-spot for camembert-base: batch 32. Larger batches
    # (tested 128) actually slow throughput on M4 Max — MPS kernel
    # launches don't amortize the way CUDA does. num_workers=0 because
    # sentence-transformers' fit() collate_fn is a local closure that
    # can't pickle for spawned workers.
    train_loader = DataLoader(
        train_examples,
        shuffle=True,
        batch_size=32,
        num_workers=0,
        pin_memory=False,
    )

    # MultipleNegativesRankingLoss: each anchor's positive is the target;
    # all other positives in the batch are treated as negatives. Very
    # efficient — no need to mine negatives explicitly.
    train_loss = losses.MultipleNegativesRankingLoss(model)

    # Cheap valid metric: anchor-vs-positive cosine similarity.
    valid_evaluator = evaluation.EmbeddingSimilarityEvaluator(
        sentences1=[a for a, _ in valid],
        sentences2=[p for _, p in valid],
        scores=[1.0] * len(valid),  # all pairs are positives; just measure mean cosine
        name="valid",
    )

    MODEL_OUT.mkdir(parents=True, exist_ok=True)
    model.fit(
        train_objectives=[(train_loader, train_loss)],
        evaluator=valid_evaluator,
        evaluation_steps=2000,
        epochs=1,
        warmup_steps=500,
        output_path=str(MODEL_OUT),
        show_progress_bar=True,
        save_best_model=True,
    )

    print(f"\nsaved to {MODEL_OUT}")
    print("run scripts/clue_generation/eval_filter.py to validate against human ratings")


if __name__ == "__main__":
    main()
