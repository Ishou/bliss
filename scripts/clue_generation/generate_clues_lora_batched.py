#!/usr/bin/env python3
"""Batched LoRA inference for production-scale clue generation.

Uses mlx_lm.batch_generate to process N prompts at once on MPS, which
is 3-5x faster than the sequential generate() in generate_clues_lora.py.

Trade-offs vs the single-prompt version:
- No validate-and-retry (batches can't easily restart partial failures).
  Validator runs once; non-ok rows are kept with the flag and downstream
  filter drops them.
- max_tokens fixed (no early-stop per prompt).
- Memory: scales with batch_size × max_tokens × model size. On M4 Max
  64GB, batch=16 with command-r 4-bit fits comfortably.

Resume-safe: rewrites output file every BATCH_FLUSH_EVERY batches so a
kill mid-run preserves work.

Usage:
    python scripts/clue_generation/generate_clues_lora_batched.py \\
        --model mlx-community/c4ai-command-r-08-2024-4bit \\
        --adapter models/lora-clue-v3 \\
        --test data/eval/production/sample.jsonl \\
        --output data/eval/production/lemma_clues_raw.csv \\
        --batch-size 16
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts" / "eval"))

from morphology_index import MorphologyIndex  # noqa: E402
from validate_clue import validate_lemma_clue  # noqa: E402

POS_NORMALIZE = {
    "verbe": "verbe", "nom": "nom",
    "adjectif": "adj", "adj": "adj",
    "adverbe": "adv", "adv": "adv",
}


def normalize_pos(label: str) -> str:
    return POS_NORMALIZE.get(label.strip().lower(), label.strip().lower())


def parse_user_prompt(content: str) -> tuple[str, str]:
    m = re.search(r"pour:\s*(\S+)(?:\s+\[([^\]]+)\])?", content)
    if not m:
        return "", ""
    return m.group(1).lower(), (m.group(2) or "").strip()


def clean_first_line(text: str) -> str:
    for line in text.splitlines():
        line = line.strip().lstrip("-•*➜→>").strip().rstrip(".")
        for pair in ('""', "''", "[]", "()"):
            if line.startswith(pair[0]) and line.endswith(pair[1]):
                line = line[1:-1].strip()
        if line:
            return line
    return ""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--adapter", type=Path, required=True)
    parser.add_argument("--test", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--max-tokens", type=int, default=24)
    parser.add_argument("--temp", type=float, default=0.3)
    # Cross-lingual anchor (post-iter17 diagnostic, 2026-05-11).
    # Prepends a French system message via the chat template's
    # <|SYSTEM_TOKEN|> slot. command-r 08-2024 supports this — see
    # https://huggingface.co/CohereForAI/c4ai-command-r-08-2024 chat_template.
    # Combined with lowercased lemmas in the prompt (vs the historical
    # UPPERCASE format) this flips the hardest English-leak cases:
    # `user → Ébrécher` (was Consommateur), `errer → Vagabonder`
    # (was Égarer), `clair → Net` (was Évidemment), `triple → Multiplié
    # par trois` (was Voyage à trois). `cane` remains untouchable
    # without a fresh SFT base (iter19 target).
    parser.add_argument(
        "--system-prompt",
        default=("Tu es un générateur de définitions pour mots fléchés en français. "
                 "Tu réponds toujours en français, jamais en anglais. "
                 "Ta réponse est une définition courte, idiomatique, "
                 "sans le mot à deviner."),
        help="System message prepended to every prompt via chat template. "
             "Empty string disables the system message.",
    )
    parser.add_argument("--lexique", type=Path,
                        default=Path(os.path.expanduser(
                            "~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt")))
    args = parser.parse_args()

    if not args.test.exists():
        sys.exit(f"missing {args.test}")
    if not args.lexique.exists():
        sys.exit(f"missing lexique {args.lexique}")

    print(f"loading model {args.model} + adapter {args.adapter}...", file=sys.stderr)
    from mlx_lm import load, batch_generate
    from mlx_lm.sample_utils import make_sampler

    model, tokenizer = load(args.model, adapter_path=str(args.adapter))

    print(f"loading morphology index...", file=sys.stderr)
    index = MorphologyIndex.load(args.lexique)

    # Resume: keep any row with non-empty clue from prior partial run
    existing: dict[str, dict] = {}
    if args.output.exists():
        with args.output.open(encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                if r.get("lemma_clue"):
                    existing[r["lemma"]] = r

    # Load all test items
    items: list[dict] = []
    with args.test.open(encoding="utf-8") as f:
        for line in f:
            obj = json.loads(line)
            user = obj["messages"][0]["content"]
            lemma, pos = parse_user_prompt(user)
            if not lemma or lemma in existing:
                continue
            items.append({"lemma": lemma, "pos": pos, "user": user})
    print(f"todo: {len(items)} (cached: {len(existing)})", file=sys.stderr)

    completed = dict(existing)
    fieldnames = ["lemma", "pos", "definition", "synonyms",
                  "lemma_clue", "attempts", "validation_flag", "rating"]

    def flush() -> None:
        rows = sorted(completed.values(), key=lambda r: r["lemma"])
        tmp = args.output.with_suffix(args.output.suffix + ".tmp")
        with tmp.open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=fieldnames, lineterminator="\n")
            w.writeheader()
            for r in rows:
                w.writerow({k: r.get(k, "") for k in fieldnames})
        tmp.replace(args.output)

    sampler = make_sampler(temp=args.temp)
    total_batches = (len(items) + args.batch_size - 1) // args.batch_size
    started = time.time()
    last_flush_batch = 0

    for bi in range(total_batches):
        batch = items[bi * args.batch_size : (bi + 1) * args.batch_size]
        if not batch:
            break

        # Tokenize each prompt with chat template, optionally prepending
        # a system message for French language anchoring.
        prompts: list[list[int]] = []
        for item in batch:
            msgs: list[dict] = []
            if args.system_prompt:
                msgs.append({"role": "system", "content": args.system_prompt})
            msgs.append({"role": "user", "content": item["user"]})
            chat = tokenizer.apply_chat_template(
                msgs, tokenize=True, add_generation_prompt=True,
            )
            prompts.append(chat)

        # Run batched generation
        try:
            response = batch_generate(
                model, tokenizer, prompts,
                max_tokens=args.max_tokens, sampler=sampler, verbose=False,
            )
        except ZeroDivisionError:
            # Known mlx-lm bug on stats — patched but defensive fallback
            response = None
        if response is None or not getattr(response, "texts", None):
            # fallback: single-prompt generate (slow but correct)
            from mlx_lm import generate
            outputs = []
            for item, p in zip(batch, prompts):
                chat = tokenizer.apply_chat_template(
                    [{"role": "user", "content": item["user"]}],
                    tokenize=False, add_generation_prompt=True,
                )
                outputs.append(generate(model, tokenizer, chat,
                                         max_tokens=args.max_tokens,
                                         sampler=sampler, verbose=False))
        else:
            outputs = response.texts

        for item, raw in zip(batch, outputs):
            lemma = item["lemma"]
            pos = item["pos"]
            target_pos = normalize_pos(pos)
            clue = clean_first_line(raw)
            vr = validate_lemma_clue(clue, lemma, target_pos, index)
            completed[lemma] = {
                "lemma": lemma, "pos": pos,
                "definition": "", "synonyms": "",
                "lemma_clue": clue,
                "attempts": "1",
                "validation_flag": vr.flag,
                "rating": "",
            }

        # Throttled flush + progress
        done = len(completed) - len(existing)
        if bi - last_flush_batch >= 5 or bi == total_batches - 1:
            flush()
            last_flush_batch = bi
        if bi % 10 == 0 or bi == total_batches - 1:
            elapsed = time.time() - started
            rate = done / elapsed if elapsed > 0 else 0
            eta_min = (len(items) - done) / max(rate, 0.001) / 60
            print(
                f"  batch {bi+1}/{total_batches} | "
                f"done={done}/{len(items)} | "
                f"rate={rate:.1f}/s | ETA={eta_min:.0f}m",
                flush=True,
            )

    flush()
    elapsed = time.time() - started
    print(f"\nWrote {len(completed)} rows in {elapsed:.0f}s "
          f"({(len(completed)-len(existing))/max(elapsed,0.001):.1f} clue/s)")


if __name__ == "__main__":
    main()
