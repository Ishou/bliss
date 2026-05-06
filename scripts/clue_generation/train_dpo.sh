#!/usr/bin/env bash
# Wrapper for mlx-lm-lora DPO training. The -u flag forces unbuffered stdout
# so `tqdm.write` calls (Val loss, Iter loss, save messages) flush
# immediately into the log file instead of being block-buffered until the
# process exits. Without -u, val results are invisible mid-train.
#
# Usage:
#   bash scripts/clue_generation/train_dpo.sh \
#       --config scripts/clue_generation/lora_iter13_dpo.yaml \
#       --train --train-mode dpo \
#       --beta 0.1 --dpo-cpo-loss-type sigmoid \
#       2>&1 | tee /tmp/iter13_train.log
#
# All extra arguments are forwarded verbatim to mlx_lm_lora.train.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"

PY="${PY:-.venv/bin/python}"
exec "$PY" -u -m mlx_lm_lora.train "$@"
