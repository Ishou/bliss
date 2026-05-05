#!/usr/bin/env python3
"""One cycle of active-learning filter improvement.

Per cycle:
  1. Sample N fresh lemmas (not in any prior set)
  2. Generate iter10 baseline clues (single-shot, temp 0.3)
  3. Score with current filter
  4. Active sampling: pick boundary cases (score 0.55-0.70) +
     high-score (≥0.80) + low-score (<0.40)
  5. Output: a CSV ready for rating, with score and ask "rate y/b/n"
  6. After rating, run with --add-rated to update training corpus
  7. Retrain filter (filter v_n+1)
  8. Re-evaluate on held-out 246 + 100-fresh + this cycle's data

Usage:
    # Cycle N step 1: produce candidates to rate
    python active_learning_cycle.py prepare --cycle 1 --n 500

    # Cycle N step 2: after rating, retrain
    python active_learning_cycle.py train --cycle 1

The cycle directory is data/lora/active/cycle_N/ and contains:
    candidates.csv   - all generated, scored
    to_rate.csv      - active-learning sampled, ready for rating
    rated.csv        - after rating (you fill the rating column)
    metrics.json     - precision/coverage on held-out + this cycle
"""

from __future__ import annotations

import argparse
import csv
import json
import random
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
ACTIVE_DIR = REPO / "data" / "lora" / "active"
GENERATOR_MODEL = "mlx-community/c4ai-command-r-08-2024-4bit"
GENERATOR_ADAPTER = REPO / "models" / "lora-clue-v3"
LEXIQUE = Path("/Users/isho/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt")


def existing_lemmas() -> set[str]:
    """Every lemma we've already used for training/eval/test."""
    seen: set[str] = set()
    for path in [
        REPO / "data" / "curated" / "fr.csv",
        REPO / "data" / "lora" / "synthetic_lemmas.csv",
        REPO / "data" / "lora" / "synthetic_clues.csv",
        REPO / "data" / "lora" / "round2_rated.csv",
        REPO / "data" / "eval" / "iter10_fresh100.csv",
        REPO / "data" / "eval" / "iter10_round2_1k.csv",
        REPO / "data" / "eval" / "iter10_top1k_200.csv",
        *(REPO / "data" / "eval").glob("lemma_clues_iter*.csv"),
        *(REPO / "data" / "eval").glob("lemma_clues_run_*.csv"),
    ]:
        if not path.exists():
            continue
        with path.open(encoding="utf-8") as f:
            for r in csv.DictReader(f):
                l = (r.get("lemma") or r.get("word", "")).strip().lower()
                if l:
                    seen.add(l)
    for jpath in (REPO / "data" / "lora").glob("test_*.jsonl"):
        with jpath.open(encoding="utf-8") as f:
            for line in f:
                m = json.loads(line)["messages"][0]["content"]
                m2 = re.search(r"pour:\s*(\S+)", m)
                if m2:
                    seen.add(m2.group(1).lower())
    # Plus all prior cycles
    for cdir in ACTIVE_DIR.glob("cycle_*"):
        for csvf in cdir.glob("*.csv"):
            with csvf.open(encoding="utf-8") as f:
                for r in csv.DictReader(f):
                    l = (r.get("lemma") or "").strip().lower()
                    if l:
                        seen.add(l)
    return seen


def cmd_prepare(args: argparse.Namespace) -> None:
    """Sample lemmas, generate, score, output to_rate.csv."""
    from collections import defaultdict

    cycle_dir = ACTIVE_DIR / f"cycle_{args.cycle:02d}"
    cycle_dir.mkdir(parents=True, exist_ok=True)

    excluded = existing_lemmas()
    print(f"excluding {len(excluded):,} already-seen lemmas", file=sys.stderr)

    # Sample N from top-1000-per-length
    ws = REPO / "grid" / "api" / "src" / "main" / "resources" / "words" / "words-fr.csv"
    candidates = defaultdict(list)
    with ws.open(encoding="utf-8") as f:
        for r in csv.DictReader(f):
            lemma = (r.get("lemma") or r.get("word", "")).strip().lower()
            word = r.get("word", "").strip().lower()
            if not lemma or lemma != word or not lemma.isalpha():
                continue
            L = len(lemma)
            if not (4 <= L <= 12):
                continue
            if lemma in excluded:
                continue
            try:
                freq = float(r.get("frequency") or 0)
            except ValueError:
                freq = 0
            candidates[L].append((lemma, freq))

    rng = random.Random(20260510 + args.cycle)
    chosen = []
    per_len = max(1, args.n // 9)
    for L in range(4, 13):
        bucket = sorted(candidates.get(L, []), key=lambda x: -x[1])[:1000]
        n_pick = min(per_len, len(bucket))
        chosen.extend(rng.sample(bucket, n_pick))
    chosen = chosen[: args.n]
    print(f"sampled {len(chosen)} lemmas", file=sys.stderr)

    # Generate
    print("loading generator...", file=sys.stderr)
    from mlx_lm import load, generate
    from mlx_lm.sample_utils import make_sampler

    gen_model, tokenizer = load(GENERATOR_MODEL, adapter_path=str(GENERATOR_ADAPTER))
    sampler = make_sampler(temp=0.3)
    rows = []
    for i, (lemma, _freq) in enumerate(chosen, 1):
        prompt = f"Génère une définition mots-fléchés courte pour: {lemma.upper()}"
        chat = tokenizer.apply_chat_template(
            [{"role": "user", "content": prompt}],
            tokenize=False, add_generation_prompt=True,
        )
        raw = generate(gen_model, tokenizer, chat, max_tokens=32,
                        sampler=sampler, verbose=False)
        # take first non-empty line, strip leading bullets/quotes
        clue = ""
        for line in raw.splitlines():
            line = line.strip().lstrip("-•*➜→>").strip().rstrip(".")
            for pair in ('""', "''", "[]", "()"):
                if line.startswith(pair[0]) and line.endswith(pair[1]):
                    line = line[1:-1].strip()
            if line:
                clue = line
                break
        rows.append({"lemma": lemma, "lemma_clue": clue})
        if i % 20 == 0:
            print(f"  generated {i}/{len(chosen)}", file=sys.stderr)

    # Score with current filter
    print(f"scoring with filter {args.filter}...", file=sys.stderr)
    from sentence_transformers import SentenceTransformer
    from sentence_transformers.util import cos_sim
    model = SentenceTransformer(args.filter)
    le = model.encode([r["lemma"] for r in rows], convert_to_tensor=True,
                      show_progress_bar=False)
    ce = model.encode([r["lemma_clue"] for r in rows], convert_to_tensor=True,
                      show_progress_bar=False)
    for r, l, c in zip(rows, le, ce):
        r["filter_score"] = round(float(cos_sim(l.unsqueeze(0), c.unsqueeze(0)).item()), 4)

    # Save all candidates
    cands_path = cycle_dir / "candidates.csv"
    with cands_path.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["lemma", "lemma_clue", "filter_score"],
                           lineterminator="\n")
        w.writeheader()
        for r in rows:
            w.writerow(r)
    print(f"wrote {cands_path} ({len(rows)} rows)", file=sys.stderr)

    # Active sampling: 70 boundary + 15 high + 15 low = 100 to rate
    rng = random.Random(20260511 + args.cycle)
    boundary = [r for r in rows if 0.55 <= r["filter_score"] < 0.70]
    high = [r for r in rows if r["filter_score"] >= 0.80]
    low = [r for r in rows if r["filter_score"] < 0.40]
    sample = (
        rng.sample(boundary, min(70, len(boundary))) +
        rng.sample(high, min(15, len(high))) +
        rng.sample(low, min(15, len(low)))
    )
    sample.sort(key=lambda r: -r["filter_score"])

    rate_path = cycle_dir / "to_rate.csv"
    with rate_path.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["lemma", "lemma_clue", "filter_score", "rating"],
                           lineterminator="\n")
        w.writeheader()
        for r in sample:
            w.writerow({**r, "rating": ""})
    print(f"\nwrote {rate_path} ({len(sample)} rows for rating)", file=sys.stderr)
    print(f"  boundary (0.55-0.70): {min(70, len(boundary))}", file=sys.stderr)
    print(f"  high (>=0.80):        {min(15, len(high))}", file=sys.stderr)
    print(f"  low (<0.40):          {min(15, len(low))}", file=sys.stderr)
    print(f"\nNext: rate the file, then run:", file=sys.stderr)
    print(f"  python {sys.argv[0]} train --cycle {args.cycle}", file=sys.stderr)


def cmd_train(args: argparse.Namespace) -> None:
    """After rating, retrain filter on the expanded corpus."""
    cycle_dir = ACTIVE_DIR / f"cycle_{args.cycle:02d}"
    rated_path = cycle_dir / "to_rate.csv"   # rated in place
    if not rated_path.exists():
        sys.exit(f"missing {rated_path}; run prepare first")

    # Verify all rows are rated
    new_rated = []
    with rated_path.open(encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rating = (r.get("rating") or "").strip().lower()
            if rating not in ("y", "b", "n"):
                sys.exit(f"unrated row: {r['lemma']}; please complete {rated_path}")
            new_rated.append({"lemma": r["lemma"].strip().lower(),
                              "lemma_clue": r["lemma_clue"].strip(),
                              "rating": rating, "source": f"active_cycle_{args.cycle:02d}"})
    print(f"adding {len(new_rated)} rated rows from cycle {args.cycle}", file=sys.stderr)

    # Append to a master active CSV used by training
    master = ACTIVE_DIR / "all_rated.csv"
    existing = []
    if master.exists():
        with master.open(encoding="utf-8") as f:
            existing = list(csv.DictReader(f))
    combined = existing + new_rated
    with master.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["lemma", "lemma_clue", "rating", "source"],
                           lineterminator="\n")
        w.writeheader()
        for r in combined:
            w.writerow(r)
    print(f"updated {master} ({len(combined)} total rated rows)", file=sys.stderr)

    # Retrain filter v5 (which now reads active/all_rated.csv too)
    import subprocess
    print("\nretraining filter v5 with new active rated data...", file=sys.stderr)
    subprocess.run(
        [sys.executable, str(REPO / "scripts" / "clue_generation" / "train_filter_v5.py")],
        check=True,
    )

    # Quick eval on held-out 246 + 100-fresh
    print("\nevaluating new filter...", file=sys.stderr)
    from sentence_transformers import SentenceTransformer
    from sentence_transformers.util import cos_sim
    from scipy.stats import spearmanr
    from sklearn.metrics import roc_auc_score
    from collections import Counter

    model = SentenceTransformer(str(REPO / "models" / "filter-camembert-v5"))
    metrics: dict[str, dict] = {}

    for label, path in [
        ("held_out_246", REPO / "data" / "lora_filter" / "eval_human.jsonl"),
    ]:
        rows = [json.loads(l) for l in path.open()]
        le = model.encode([r["lemma"] for r in rows], convert_to_tensor=True, show_progress_bar=False)
        ce = model.encode([r["candidate"] for r in rows], convert_to_tensor=True, show_progress_bar=False)
        scores = [float(cos_sim(a.unsqueeze(0), b.unsqueeze(0)).item()) for a, b in zip(le, ce)]
        ranks = [{"y": 2, "b": 1, "n": 0}[r["rating"]] for r in rows]
        rho, _ = spearmanr(scores, ranks)
        yn = [(s, ranks[i]) for i, s in enumerate(scores) if rows[i]["rating"] in ("y", "n")]
        xs, ys = zip(*yn)
        auc = roc_auc_score([1 if y == 2 else 0 for y in ys], xs)
        metrics[label] = {"spearman": float(rho), "auroc": float(auc), "n": len(rows)}

    metrics_path = cycle_dir / "metrics.json"
    metrics_path.write_text(json.dumps(metrics, indent=2))
    print(f"\nmetrics:\n{json.dumps(metrics, indent=2)}", file=sys.stderr)
    print(f"wrote {metrics_path}", file=sys.stderr)


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("prepare", help="generate + score + sample for rating")
    p.add_argument("--cycle", type=int, required=True)
    p.add_argument("--n", type=int, default=500, help="lemmas to generate")
    p.add_argument("--filter", default=str(REPO / "models" / "filter-camembert-v5"))

    p = sub.add_parser("train", help="incorporate ratings + retrain")
    p.add_argument("--cycle", type=int, required=True)

    args = parser.parse_args()
    if args.cmd == "prepare":
        cmd_prepare(args)
    elif args.cmd == "train":
        cmd_train(args)


if __name__ == "__main__":
    main()
