#!/usr/bin/env python3
"""Probe an iter13 DPO checkpoint by generating clues for held-out test verbs.

Loads command-r-08-2024-4bit + the specified adapter once, then runs a
small fixed prompt set (the 8 verbs in our test split, plus the 3 user-cited
canonical bug examples). Output is hand-inspected: did the model emit
PP-friendly framings (single-word synonym OR verb+de+N), or did it emit
memorized train-set clues / the rejected verb+DObj framing?

Usage: python scripts/eval/probe_iter13_checkpoint.py <adapter_path>

The adapter_path must be a directory containing adapters.safetensors and
adapter_config.json (mlx-lm convention).
"""
from __future__ import annotations

import sys
from pathlib import Path

from mlx_lm import generate, load


PROMPT_TMPL = "Génère une définition mots-fléchés courte pour: {} [verbe]"


def _format(tokenizer, content: str) -> str:
    """The SFT corpus is in chat-template format (messages: user/assistant);
    DPO inherits that format. Wrap the raw prompt in the model's chat template
    so the response head fires correctly. Without this, the model autoregresses
    the prompt text instead of answering it."""
    msgs = [{"role": "user", "content": content}]
    return tokenizer.apply_chat_template(
        msgs, tokenize=False, add_generation_prompt=True,
    )

# 8 test-split verbs (held out from iter13 train) + 3 user-cited bug cases
# (these are in the iter13 TRAIN set so they're a memorization probe).
TEST_VERBS = ["ouvrir", "fermer", "vider", "remplir",
              "user", "nettoyer", "brûler", "geler"]
TRAIN_VERBS = ["forer", "accorder", "aggraver"]


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: probe_iter13_checkpoint.py <adapter_dir>", file=sys.stderr)
        return 2
    adapter_path = sys.argv[1]
    print(f"loading model + adapter at {adapter_path}…", file=sys.stderr)

    model, tokenizer = load(
        "mlx-community/c4ai-command-r-08-2024-4bit",
        adapter_path=adapter_path,
    )

    print(f"\n=== HELD-OUT TEST VERBS (iter13 never saw these) ===")
    for verb in TEST_VERBS:
        prompt = _format(tokenizer, PROMPT_TMPL.format(verb.upper()))
        out = generate(model, tokenizer, prompt=prompt,
                       max_tokens=32, verbose=False).strip()
        # Trim to first line / shortest reasonable cut.
        out = out.split("\n")[0].strip()
        print(f"{verb:12s} -> {out}")

    print(f"\n=== TRAIN-SET CANONICAL VERBS (memorization probe) ===")
    for verb in TRAIN_VERBS:
        prompt = _format(tokenizer, PROMPT_TMPL.format(verb.upper()))
        out = generate(model, tokenizer, prompt=prompt,
                       max_tokens=32, verbose=False).strip()
        out = out.split("\n")[0].strip()
        print(f"{verb:12s} -> {out}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
