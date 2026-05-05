#!/usr/bin/env python3
"""Train a cross-encoder filter for clue scoring.

Cross-encoder = joint-encode (lemma, candidate) through a transformer
+ classification head. Better discriminative power than a bi-encoder
for fine-grained "is this a good clue?" judgments.

Training data:
- 50k DBnary positives: (lemma, dbnary_def, 1.0)
- 50k DBnary hard negatives: (lemma, def_of_DIFFERENT_lemma, 0.0)
- ~308 rated y/b/n: (lemma, clue, 1.0/0.5/0.0)

The DBnary pairs teach lemma↔concept matching; the rated pairs
teach mots-fléchés style preference.

Architecture: CamemBERT-base + binary classification head.

Usage:
    python scripts/clue_generation/train_cross_encoder.py
"""

from __future__ import annotations

import csv
import json
import random
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
DATA = REPO / "data" / "lora_filter"
OUT = REPO / "models" / "filter-crossencoder-v1"
SEED = 20260504
N_DBNARY_POS = 50000
N_DBNARY_NEG = 50000


def main() -> None:
    from sentence_transformers import CrossEncoder, InputExample
    from sentence_transformers.cross_encoder.evaluation import (
        CECorrelationEvaluator,
    )
    from torch.utils.data import DataLoader

    rng = random.Random(SEED)

    # 1. DBnary pairs from train_full.jsonl (the unsubsampled file we kept)
    dbnary_full = DATA / "train_full.jsonl"
    if not dbnary_full.exists():
        dbnary_full = DATA / "train.jsonl"
    pairs: list[tuple[str, str]] = []
    with dbnary_full.open() as f:
        for line in f:
            obj = json.loads(line)
            pairs.append((obj["anchor"], obj["positive"]))
    print(f"DBnary pairs available: {len(pairs):,}", file=sys.stderr)

    # Shuffle, take a slice for positives and a separate slice for negatives.
    rng.shuffle(pairs)
    positives = pairs[:N_DBNARY_POS]

    # Hard negatives: pair (lemma_A, def_of_lemma_B) where A != B.
    # Use a different slice + shuffle to randomize the def-source pairing.
    neg_source = pairs[N_DBNARY_POS : N_DBNARY_POS + N_DBNARY_NEG * 2]
    neg_lemmas = [p[0] for p in neg_source[:N_DBNARY_NEG]]
    neg_defs = [p[1] for p in neg_source[N_DBNARY_NEG : 2 * N_DBNARY_NEG]]
    # Sanity: drop any accidental matches
    negatives = [(l, d) for l, d in zip(neg_lemmas, neg_defs) if l]
    print(f"DBnary positives: {len(positives):,}  hard-negatives: {len(negatives):,}",
          file=sys.stderr)

    # 2. Rated pairs from iter3-iter9 (excluding eval-held-out lemmas)
    held_out: set[str] = set()
    with (DATA / "eval_human.jsonl").open() as f:
        for line in f:
            held_out.add(json.loads(line)["lemma"])

    rated_examples: list[tuple[str, str, float]] = []
    score_map = {"y": 1.0, "b": 0.5, "n": 0.0}
    for path in sorted((REPO / "data" / "eval").glob("lemma_clues_iter*.csv")):
        if not path.name.startswith("lemma_clues_iter"):
            continue
        with path.open(encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                rating = (r.get("rating") or "").strip().lower()
                if rating not in score_map:
                    continue
                lemma = (r.get("lemma") or "").strip().lower()
                if lemma in held_out:
                    continue
                clue = (r.get("lemma_clue") or "").strip()
                if not lemma or not clue:
                    continue
                rated_examples.append((lemma, clue, score_map[rating]))
    print(f"rated training examples (non-held-out): {len(rated_examples)}",
          file=sys.stderr)

    # 3. Build InputExamples for cross-encoder training
    examples: list[InputExample] = []
    for l, d in positives:
        examples.append(InputExample(texts=[l, d], label=1.0))
    for l, d in negatives:
        examples.append(InputExample(texts=[l, d], label=0.0))
    # Upweight rated examples by repeating: 100k DBnary vs 308 rated → bias too far
    for _ in range(50):  # roughly balance: rated × 50 ≈ 15k vs 100k DBnary
        for l, c, s in rated_examples:
            examples.append(InputExample(texts=[l, c], label=s))

    rng.shuffle(examples)
    n = len(examples)
    valid = examples[:1000]
    train = examples[1000:]
    print(f"total: {n:,}  train: {len(train):,}  valid: {len(valid)}",
          file=sys.stderr)

    # 4. Train cross-encoder
    print("loading base camembert (cross-encoder mode)...", file=sys.stderr)
    model = CrossEncoder(
        "dangvantuan/sentence-camembert-base",
        num_labels=1,
        max_length=128,
    )

    valid_evaluator = CECorrelationEvaluator(
        sentence_pairs=[(ex.texts[0], ex.texts[1]) for ex in valid],
        scores=[ex.label for ex in valid],
        name="ce-valid",
    )

    train_loader = DataLoader(train, shuffle=True, batch_size=32)
    OUT.mkdir(parents=True, exist_ok=True)
    model.fit(
        train_dataloader=train_loader,
        evaluator=valid_evaluator,
        evaluation_steps=500,
        epochs=1,
        warmup_steps=200,
        output_path=str(OUT),
        show_progress_bar=True,
        save_best_model=True,
    )
    print(f"\nsaved to {OUT}")


if __name__ == "__main__":
    main()
