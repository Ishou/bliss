#!/usr/bin/env python3
"""Generate clues using a trained LoRA adapter via mlx-lm.

Reads a JSONL of {"messages":[user, assistant]} pairs (the LoRA test
split format) and produces a CSV matching the existing eval schema:
    lemma, pos, definition, synonyms, lemma_clue, attempts, validation_flag, rating

Validation reuses scripts/eval/validate_clue.py exactly. Retry pattern
mirrors generate_clues_lemma.py: 3 attempts at temperatures 0.3 / 0.6
/ 0.9; first ok wins. Persists incrementally so a kill mid-run keeps
work.

Usage:
    python scripts/clue_generation/generate_clues_lora.py \\
        --model mlx-community/c4ai-command-r-08-2024-4bit \\
        --adapter models/lora-clue-v1 \\
        --test data/lora/test.jsonl \\
        --output data/eval/lemma_clues_iter8.csv
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

# scripts/eval/ is needed for validate_clue + morphology_index
REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts" / "eval"))

from morphology_index import MorphologyIndex  # noqa: E402
from validate_clue import ValidationResult, validate_lemma_clue  # noqa: E402

ATTEMPT_TEMPS = (0.3, 0.6, 0.9)
MAX_TOKENS = 32
MAX_ATTEMPTS = 3
FLUSH_EVERY = 10

POS_NORMALIZE = {
    "verbe": "verbe", "nom": "nom",
    "adjectif": "adj", "adj": "adj",
    "adverbe": "adv", "adv": "adv",
}


def normalize_pos(label: str) -> str:
    return POS_NORMALIZE.get(label.strip().lower(), label.strip().lower())


def parse_user_prompt(content: str) -> tuple[str, str]:
    """Extract (lemma, pos) from the prompt format build_corpus.py uses:
       "Génère une définition mots-fléchés courte pour: LEMMA [pos]"
    """
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
    parser.add_argument("--lexique", type=Path, default=Path(
        os.path.expanduser("~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt")))
    parser.add_argument("--limit", type=int, default=None)
    args = parser.parse_args()

    if not args.test.exists():
        sys.exit(f"missing {args.test}")
    if not args.lexique.exists():
        sys.exit(f"missing lexique: {args.lexique}")

    print(f"loading model {args.model} + adapter {args.adapter}...", file=sys.stderr)
    from mlx_lm import load, generate  # noqa: E402
    from mlx_lm.sample_utils import make_sampler

    model, tokenizer = load(args.model, adapter_path=str(args.adapter))

    print(f"loading morphology index from {args.lexique}...", file=sys.stderr)
    index = MorphologyIndex.load(args.lexique)

    # Resume: keep any row with non-empty clue
    existing: dict[str, dict] = {}
    if args.output.exists():
        with args.output.open(encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                if r.get("lemma_clue"):
                    existing[r["lemma"]] = r

    # Load test set
    test_items: list[dict] = []
    with args.test.open(encoding="utf-8") as f:
        for line in f:
            obj = json.loads(line)
            user = obj["messages"][0]["content"]
            lemma, pos = parse_user_prompt(user)
            if not lemma:
                continue
            if lemma in existing:
                continue
            test_items.append({"lemma": lemma, "pos": pos, "user": user})
    if args.limit:
        test_items = test_items[: args.limit]

    print(f"todo: {len(test_items)} (cached: {len(existing)})", file=sys.stderr)

    completed = dict(existing)
    fieldnames = [
        "lemma", "pos", "definition", "synonyms",
        "lemma_clue", "attempts", "validation_flag", "rating",
    ]

    def flush() -> None:
        rows = sorted(completed.values(), key=lambda r: r["lemma"])
        tmp = args.output.with_suffix(args.output.suffix + ".tmp")
        with tmp.open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=fieldnames, lineterminator="\n")
            w.writeheader()
            for r in rows:
                w.writerow({k: r.get(k, "") for k in fieldnames})
        tmp.replace(args.output)

    started = time.time()
    since_flush = 0
    for idx, item in enumerate(test_items, 1):
        lemma = item["lemma"]
        pos = item["pos"]
        target_pos = normalize_pos(pos)
        prompt = tokenizer.apply_chat_template(
            [{"role": "user", "content": item["user"]}],
            tokenize=False,
            add_generation_prompt=True,
        )

        last_clue = ""
        last_flag = "no-attempt"
        attempts = 0
        for round_idx in range(MAX_ATTEMPTS):
            attempts = round_idx + 1
            sampler = make_sampler(temp=ATTEMPT_TEMPS[round_idx])
            raw = generate(model, tokenizer, prompt, max_tokens=MAX_TOKENS,
                           sampler=sampler, verbose=False)
            clue = clean_first_line(raw)
            vr = validate_lemma_clue(clue, lemma, target_pos, index)
            last_clue, last_flag = clue, vr.flag
            if vr.flag == "ok":
                break

        completed[lemma] = {
            "lemma": lemma, "pos": pos,
            "definition": "", "synonyms": "",
            "lemma_clue": last_clue,
            "attempts": str(attempts),
            "validation_flag": last_flag,
            "rating": "",
        }
        since_flush += 1
        marker = "OK" if last_flag == "ok" else last_flag
        print(f"  [{idx:3d}/{len(test_items)}] {lemma:18s} ({pos:10s}) "
              f"x{attempts} {marker:14s} -> {last_clue!r}", flush=True)
        if since_flush >= FLUSH_EVERY:
            flush()
            since_flush = 0

    flush()
    elapsed = time.time() - started

    # Quick stats
    flag_counts: dict[str, int] = {}
    for r in completed.values():
        flag_counts[r["validation_flag"]] = flag_counts.get(r["validation_flag"], 0) + 1
    print(f"\nWrote {len(completed)} rows to {args.output} in {elapsed:.1f}s")
    for f, n in sorted(flag_counts.items(), key=lambda kv: -kv[1]):
        print(f"  {f:18s} {n}")


if __name__ == "__main__":
    main()
