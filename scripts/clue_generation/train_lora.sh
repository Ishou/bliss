#!/usr/bin/env bash
# Train a LoRA adapter on the clue-gen corpus.
#
# Defaults are tuned for command-r 32B 4bit + ~85 train pairs (small
# corpus, conservative hyperparams). Override via env:
#   MODEL=mlx-community/c4ai-command-r-08-2024-4bit  (default)
#   ITERS=600   BATCH=2   LR=1e-5   RANK=16   LAYERS=16
#   ADAPTER=models/lora-clue-v1
#
# Pre-flight: bash scripts/clue_generation/train_lora.sh smoke
#   (5 iters; verifies arch + gradients before the long run)
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"

MODEL="${MODEL:-mlx-community/c4ai-command-r-08-2024-4bit}"
ITERS="${ITERS:-600}"
BATCH="${BATCH:-2}"
LR="${LR:-1e-5}"
LAYERS="${LAYERS:-16}"
ADAPTER="${ADAPTER:-models/lora-clue-v1}"
DATA="${DATA:-data/lora}"

mode="${1:-full}"
if [[ "$mode" == "smoke" ]]; then
  ITERS=5
  ADAPTER="models/lora-smoke"
  echo "[smoke] $ITERS iters, adapter=$ADAPTER"
fi

mkdir -p "$ADAPTER"

echo "[train] model=$MODEL iters=$ITERS batch=$BATCH lr=$LR layers=$LAYERS"
echo "[train] data=$DATA adapter=$ADAPTER"

# mlx-lm 0.31 CLI: --num-layers, --batch-size, --learning-rate, --adapter-path
exec .venv/bin/mlx_lm.lora \
  --train \
  --model "$MODEL" \
  --data "$DATA" \
  --iters "$ITERS" \
  --batch-size "$BATCH" \
  --learning-rate "$LR" \
  --num-layers "$LAYERS" \
  --adapter-path "$ADAPTER" \
  --steps-per-report 10 \
  --steps-per-eval 50 \
  --save-every 100 \
  --seed 20260504
