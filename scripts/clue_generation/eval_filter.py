#!/usr/bin/env python3
"""Validate the filter model against 554 human-rated clue pairs.

For each (lemma, candidate, rating) row, compute filter score
(cosine sim between embedded lemma and embedded candidate). Then
check whether scores rank y > b > n.

Metrics:
- Mean score per rating bucket (y, b, n) — should be monotonic
- Spearman correlation (score vs rating-rank) — > 0.3 is meaningful
- AUROC for "is this y?" vs "is this n?" — > 0.7 is useful

Output:
- Print summary
- Save per-row scores to data/lora_filter/eval_scored.csv
"""

from __future__ import annotations

import csv
import json
import sys
from pathlib import Path
from statistics import mean

REPO = Path(__file__).resolve().parent.parent.parent
DATA = REPO / "data" / "lora_filter"
MODEL_PATH = REPO / "models" / "filter-camembert-v1"
RATING_RANK = {"y": 2, "b": 1, "n": 0}


def main() -> None:
    from sentence_transformers import SentenceTransformer
    from sentence_transformers.util import cos_sim

    eval_rows = []
    with (DATA / "eval_human.jsonl").open(encoding="utf-8") as f:
        for line in f:
            eval_rows.append(json.loads(line))
    print(f"loaded {len(eval_rows)} eval rows", file=sys.stderr)

    print(f"loading {MODEL_PATH}...", file=sys.stderr)
    model = SentenceTransformer(str(MODEL_PATH))

    # Embed lemmas and candidates separately
    lemmas = [r["lemma"] for r in eval_rows]
    cands = [r["candidate"] for r in eval_rows]
    lemma_emb = model.encode(lemmas, convert_to_tensor=True, show_progress_bar=True)
    cand_emb = model.encode(cands, convert_to_tensor=True, show_progress_bar=True)

    scores = [float(cos_sim(le.unsqueeze(0), ce.unsqueeze(0)).item())
              for le, ce in zip(lemma_emb, cand_emb)]

    # Per-bucket mean score
    by_rating: dict[str, list[float]] = {"y": [], "b": [], "n": []}
    for r, s in zip(eval_rows, scores):
        by_rating[r["rating"]].append(s)

    print("\nMean filter score by rating:")
    for k in ("y", "b", "n"):
        vs = by_rating[k]
        print(f"  {k}: n={len(vs):3d}  mean={mean(vs):.3f}  range=[{min(vs):.3f}, {max(vs):.3f}]")

    # Spearman correlation
    try:
        from scipy.stats import spearmanr
        ranks = [RATING_RANK[r["rating"]] for r in eval_rows]
        rho, p = spearmanr(scores, ranks)
        print(f"\nSpearman correlation (score vs y>b>n rank): rho={rho:.3f} (p={p:.2e})")
        if rho > 0.4:
            print("  → strong: filter ranks human ratings well")
        elif rho > 0.2:
            print("  → moderate: useful signal, may need ensemble")
        elif rho > 0.0:
            print("  → weak: filter isn't reliably discriminating")
        else:
            print("  → broken: filter doesn't track human judgments")
    except ImportError:
        print("\n(scipy not installed; skipping Spearman)")

    # AUROC for y vs n (drop b)
    yn = [(s, RATING_RANK[r["rating"]]) for r, s in zip(eval_rows, scores)
          if r["rating"] in ("y", "n")]
    if yn:
        try:
            from sklearn.metrics import roc_auc_score
            xs, ys = zip(*yn)
            ys_bin = [1 if y == 2 else 0 for y in ys]
            auc = roc_auc_score(ys_bin, xs)
            print(f"\nAUROC (y vs n only, n={len(yn)}): {auc:.3f}")
            if auc > 0.7:
                print("  → useful filter signal")
            elif auc > 0.6:
                print("  → marginal signal")
            else:
                print("  → essentially chance; filter not working")
        except ImportError:
            pass

    # Persist scored rows for inspection
    out = DATA / "eval_scored.csv"
    with out.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["lemma", "candidate", "rating", "filter_score"])
        for r, s in zip(eval_rows, scores):
            w.writerow([r["lemma"], r["candidate"], r["rating"], f"{s:.4f}"])
    print(f"\nwrote {out}")


if __name__ == "__main__":
    main()
