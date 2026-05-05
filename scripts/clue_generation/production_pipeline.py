#!/usr/bin/env python3
"""End-to-end production clue pipeline.

For each input lemma:
  1. Generate K=5 candidates with iter10 LoRA at varied temperatures
  2. Score each candidate with filter-camembert-v2 (semantic ranker)
  3. Apply structural gates: validator ok, length 1-6 words, no stem-leak
  4. Among gate-passing candidates, pick the highest filter_score
  5. Ship if its filter_score >= T (default 0.7); otherwise drop word

Output CSV per lemma:
  lemma, chosen_clue, filter_score, n_candidates_generated,
  n_candidates_passed_gates, status (shipped|dropped|no-candidates)

Usage:
    python scripts/clue_generation/production_pipeline.py \\
        --test data/lora/test.jsonl \\
        --output data/eval/iter13_pipeline.csv
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
from validate_clue import (  # noqa: E402
    ValidationResult, validate_lemma_clue, _find_stem_leak,
)

K_CANDIDATES = 5
TEMPERATURES = (0.3, 0.5, 0.7, 0.9, 1.1)
MAX_TOKENS = 32
DEFAULT_THRESHOLD = 0.7

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


def length_score(clue: str) -> float:
    n = len(clue.split())
    if 1 <= n <= 6:
        return 1.0
    if n == 0:
        return 0.0
    if n <= 8:
        return 1.0 - 0.2 * (n - 6)
    return max(0.0, 0.6 - 0.1 * (n - 8))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--generator-model",
                        default="mlx-community/c4ai-command-r-08-2024-4bit")
    parser.add_argument("--generator-adapter",
                        type=Path, default=REPO / "models" / "lora-clue-v3")
    parser.add_argument("--filter-model",
                        type=Path, default=REPO / "models" / "filter-camembert-v2")
    parser.add_argument("--test", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD)
    parser.add_argument("--lexique", type=Path,
                        default=Path(os.path.expanduser(
                            "~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt")))
    parser.add_argument("--limit", type=int, default=None)
    args = parser.parse_args()

    if not args.test.exists():
        sys.exit(f"missing {args.test}")
    if not args.lexique.exists():
        sys.exit(f"missing lexique {args.lexique}")

    # Load filter
    print(f"loading filter {args.filter_model}...", file=sys.stderr)
    from sentence_transformers import SentenceTransformer
    from sentence_transformers.util import cos_sim
    filter_model = SentenceTransformer(str(args.filter_model))

    # Load morphology index
    print(f"loading morphology index...", file=sys.stderr)
    index = MorphologyIndex.load(args.lexique)

    # Load generator
    print(f"loading generator {args.generator_model}+{args.generator_adapter}...",
          file=sys.stderr)
    from mlx_lm import load, generate
    from mlx_lm.sample_utils import make_sampler
    gen_model, tokenizer = load(args.generator_model,
                                 adapter_path=str(args.generator_adapter))

    # Load test items (deduped by lemma)
    items: dict[str, dict] = {}
    with args.test.open(encoding="utf-8") as f:
        for line in f:
            obj = json.loads(line)
            user = obj["messages"][0]["content"]
            lemma, pos = parse_user_prompt(user)
            if not lemma or lemma in items:
                continue
            items[lemma] = {"lemma": lemma, "pos": pos, "user": user}
    items_list = list(items.values())
    if args.limit:
        items_list = items_list[: args.limit]
    print(f"items to process: {len(items_list)}", file=sys.stderr)

    out_rows: list[dict] = []
    started = time.time()
    for idx, item in enumerate(items_list, 1):
        lemma = item["lemma"]
        pos = item["pos"]
        target_pos = normalize_pos(pos)

        # 1) Generate K candidates at varied temperatures
        prompt = tokenizer.apply_chat_template(
            [{"role": "user", "content": item["user"]}],
            tokenize=False, add_generation_prompt=True,
        )
        candidates: list[str] = []
        for t in TEMPERATURES:
            sampler = make_sampler(temp=t)
            raw = generate(gen_model, tokenizer, prompt,
                           max_tokens=MAX_TOKENS, sampler=sampler, verbose=False)
            c = clean_first_line(raw)
            if c and c not in candidates:
                candidates.append(c)
            if len(candidates) >= K_CANDIDATES:
                break

        # 2) Apply structural gates
        gated: list[tuple[str, float]] = []
        for c in candidates:
            vr = validate_lemma_clue(c, lemma, target_pos, index)
            if vr.flag != "ok":
                continue
            if _find_stem_leak(c, lemma):
                continue
            n_words = len(c.split())
            if not (1 <= n_words <= 6):
                continue
            gated.append((c, length_score(c)))

        if not gated:
            out_rows.append({
                "lemma": lemma, "pos": pos,
                "chosen_clue": "", "filter_score": "",
                "n_candidates_generated": str(len(candidates)),
                "n_candidates_passed_gates": "0",
                "status": "no-candidates",
            })
            print(f"  [{idx:3d}/{len(items_list)}] {lemma:18s} no-candidates "
                  f"(generated {len(candidates)})", flush=True)
            continue

        # 3) Score gated candidates with filter
        gated_clues = [c for c, _ in gated]
        le = filter_model.encode([lemma] * len(gated_clues),
                                  convert_to_tensor=True, show_progress_bar=False)
        ce = filter_model.encode(gated_clues, convert_to_tensor=True,
                                  show_progress_bar=False)
        scores = [float(cos_sim(a.unsqueeze(0), b.unsqueeze(0)).item())
                  for a, b in zip(le, ce)]

        # 4) Pick top-scored
        ranked = sorted(zip(gated_clues, scores), key=lambda x: -x[1])
        top_clue, top_score = ranked[0]

        # 5) Ship only if score >= threshold
        if top_score >= args.threshold:
            status = "shipped"
        else:
            status = "below-threshold"

        out_rows.append({
            "lemma": lemma, "pos": pos,
            "chosen_clue": top_clue if status == "shipped" else "",
            "filter_score": f"{top_score:.3f}",
            "n_candidates_generated": str(len(candidates)),
            "n_candidates_passed_gates": str(len(gated)),
            "status": status,
        })
        marker = "✓" if status == "shipped" else "✗"
        print(f"  [{idx:3d}/{len(items_list)}] {lemma:18s} {marker} "
              f"score={top_score:.3f} ({status}) -> {top_clue!r}", flush=True)

    # Write
    fieldnames = ["lemma", "pos", "chosen_clue", "filter_score",
                  "n_candidates_generated", "n_candidates_passed_gates", "status"]
    with args.output.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        w.writeheader()
        for r in out_rows:
            w.writerow(r)

    elapsed = time.time() - started
    n_shipped = sum(1 for r in out_rows if r["status"] == "shipped")
    n_below = sum(1 for r in out_rows if r["status"] == "below-threshold")
    n_none = sum(1 for r in out_rows if r["status"] == "no-candidates")
    print(f"\nWrote {len(out_rows)} rows to {args.output} in {elapsed:.1f}s")
    print(f"  shipped:           {n_shipped}/{len(out_rows)} "
          f"({100*n_shipped/max(1,len(out_rows)):.1f}%)")
    print(f"  below-threshold:   {n_below}")
    print(f"  no-candidates:     {n_none}")


if __name__ == "__main__":
    main()
