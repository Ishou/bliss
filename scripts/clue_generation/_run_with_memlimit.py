#!/usr/bin/env python3
"""Wrapper: cap MLX memory at $MLX_MEMORY_LIMIT_GB (default 32 GB), then
run any python script given as argv[1]. Forwards argv[2:] to the script.

Use this for inference scripts (generate_clues_lora_batched.py etc.) that
need a memory cap but aren't mlx_lm_lora.train. For training, use
_train_with_memlimit.py — it bypasses argparse via sys.argv and is the
expected entry point for `mlx_lm_lora.train.main()`."""
import os
import runpy
import sys

LIMIT_GB = int(os.environ.get("MLX_MEMORY_LIMIT_GB", "32"))

import mlx.core as mx
try:
    prior = mx.set_memory_limit(LIMIT_GB * 1024 ** 3)
    print(f"[memlimit] MLX memory cap: {LIMIT_GB} GB "
          f"(prior limit: {prior / 1024**3:.1f} GB)", file=sys.stderr, flush=True)
except Exception as e:
    print(f"[memlimit] WARN: mx.set_memory_limit failed: {e!r}",
          file=sys.stderr, flush=True)

# Daemon thread: log MLX active + peak memory every 30s. macOS RSS via
# `ps` only sees CPU allocations — unified-memory GPU buffers (the bulk
# of MLX usage) are invisible from outside the process. Logging from
# inside via mx.metal API is the only reliable way to titrate batch size.
def _mem_logger():
    import time
    while True:
        try:
            active = mx.metal.get_active_memory() / 1024**3
            peak   = mx.metal.get_peak_memory() / 1024**3
            print(f"[memprobe] active={active:.2f} GB  peak={peak:.2f} GB",
                  file=sys.stderr, flush=True)
        except Exception:
            pass
        time.sleep(30)

import threading
threading.Thread(target=_mem_logger, daemon=True).start()

if len(sys.argv) < 2:
    sys.exit("usage: _run_with_memlimit.py <script.py> [args...]")

target = sys.argv[1]
sys.argv = sys.argv[1:]  # drop the wrapper from argv so the target sees its own argv[0]
runpy.run_path(target, run_name="__main__")
