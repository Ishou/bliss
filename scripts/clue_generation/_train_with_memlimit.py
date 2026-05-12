#!/usr/bin/env python3
"""Wrapper: cap MLX memory at $MLX_MEMORY_LIMIT_GB (default 32 GB), then
invoke mlx_lm_lora.train.main(). Forwards all argv after this script.

Used by train_dpo.sh when iter19+ runs need to share the GPU with other
work (mlx command-r 4-bit fits in ~17 GB; with reference model + KV
cache + activations a DPO run otherwise climbs toward 50-60 GB)."""
import os
import sys

LIMIT_GB = int(os.environ.get("MLX_MEMORY_LIMIT_GB", "24"))

import mlx.core as mx
try:
    prior = mx.set_memory_limit(LIMIT_GB * 1024 ** 3)
    print(f"[memlimit] MLX memory cap: {LIMIT_GB} GB "
          f"(prior limit: {prior / 1024**3:.1f} GB)", file=sys.stderr, flush=True)
except Exception as e:
    print(f"[memlimit] WARN: mx.set_memory_limit failed: {e!r}", file=sys.stderr, flush=True)

# Hand off control to mlx_lm_lora.train.main() — it parses sys.argv itself.
from mlx_lm_lora.train import main
main()
