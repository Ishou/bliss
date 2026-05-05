#!/usr/bin/env python3
"""Ensemble filter: combine the bi-encoder filter (semantic signal)
with structural / heuristic signals into a single accept/reject score.

Signals stacked:
- f_sem: bi-encoder cosine similarity (Phase 1+2 model). Range [0,1].
- f_val: validate_lemma_clue verdict — 1 if "ok", 0 otherwise.
- f_len: length penalty (favor 1-6 word clues).
- f_freq: head-token frequency (penalize clue heads with rare/unknown forms).
- f_noref: 1 if no clue token shares a 5+ char stem with the lemma.

Final score = weighted combination, calibrated against held-out human
ratings. Evaluate Spearman/AUROC on 246 rows.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from statistics import mean


REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts" / "eval"))

from morphology_index import MorphologyIndex  # noqa: E402
from validate_clue import validate_lemma_clue, _find_stem_leak, _TOKEN_RE  # noqa: E402

DATA = REPO / "data" / "lora_filter"
FILTER_MODEL = REPO / "models" / "filter-camembert-v2"

POS_NORMALIZE = {
    "verbe": "verbe", "nom": "nom", "adjectif": "adj", "adj": "adj",
    "adverbe": "adv", "adv": "adv",
}


def normalize_pos(label: str) -> str:
    return POS_NORMALIZE.get(label.strip().lower(), label.strip().lower())


def length_score(clue: str) -> float:
    """Favor clues with 1-6 words. Linear penalty outside that range."""
    n = len(clue.split())
    if 1 <= n <= 6:
        return 1.0
    if n == 0:
        return 0.0
    if n <= 8:
        return 1.0 - 0.2 * (n - 6)   # 8 words → 0.6
    return max(0.0, 0.6 - 0.1 * (n - 8))  # 12 words → 0.2, 14+ → 0


def main() -> None:
    from sentence_transformers import SentenceTransformer
    from sentence_transformers.util import cos_sim
    from scipy.stats import spearmanr
    from sklearn.metrics import roc_auc_score
    from sklearn.linear_model import LogisticRegression

    # 1) Load eval rows + features
    rows = []
    with (DATA / "eval_human.jsonl").open(encoding="utf-8") as f:
        for line in f:
            rows.append(json.loads(line))
    print(f"eval rows: {len(rows)}", file=sys.stderr)

    print("loading morphology index...", file=sys.stderr)
    index = MorphologyIndex.load(
        Path("/Users/isho/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt")
    )

    print(f"loading filter model {FILTER_MODEL}...", file=sys.stderr)
    model = SentenceTransformer(str(FILTER_MODEL))

    # 2) Compute features per row
    lemmas = [r["lemma"] for r in rows]
    cands = [r["candidate"] for r in rows]
    le = model.encode(lemmas, convert_to_tensor=True, show_progress_bar=False)
    ce = model.encode(cands, convert_to_tensor=True, show_progress_bar=False)
    f_sem = [float(cos_sim(a.unsqueeze(0), b.unsqueeze(0)).item())
             for a, b in zip(le, ce)]

    f_val = []
    f_noref = []
    for r in rows:
        lemma = r["lemma"]
        candidate = r["candidate"]
        # Validator without POS hint (use first available POS class)
        pos_classes = index.pos_classes_of_form(lemma)
        target_pos = pos_classes[0] if pos_classes else ""
        target_pos = normalize_pos(target_pos)
        vr = validate_lemma_clue(candidate, lemma, target_pos, index)
        f_val.append(1.0 if vr.flag == "ok" else 0.0)
        # Stem-leak check
        f_noref.append(0.0 if _find_stem_leak(candidate, lemma) else 1.0)

    f_len = [length_score(r["candidate"]) for r in rows]

    # 3) Single-feature performance for context
    print("\nSingle-feature performance:")
    RATING_RANK = {"y": 2, "b": 1, "n": 0}
    ranks = [RATING_RANK[r["rating"]] for r in rows]
    yn_idx = [i for i, r in enumerate(rows) if r["rating"] in ("y", "n")]
    yn_labels = [1 if rows[i]["rating"] == "y" else 0 for i in yn_idx]

    for name, feat in [
        ("f_sem", f_sem), ("f_val", f_val), ("f_len", f_len), ("f_noref", f_noref),
    ]:
        rho, _ = spearmanr(feat, ranks)
        auc = roc_auc_score(yn_labels, [feat[i] for i in yn_idx])
        print(f"  {name:10s} rho={rho:+.3f}  AUROC={auc:.3f}")

    # 4) Train a small logistic regression on the 4 features against
    #    binary y vs not-y. Cross-validate on the eval rows themselves
    #    (fair because we're learning weights, not labels).
    import numpy as np

    X = np.array(list(zip(f_sem, f_val, f_len, f_noref)))
    y_bin = np.array([1 if r["rating"] == "y" else 0 for r in rows])

    from sklearn.model_selection import cross_val_predict, cross_val_score
    clf = LogisticRegression(max_iter=1000)
    proba = cross_val_predict(clf, X, y_bin, cv=5, method="predict_proba")[:, 1]
    print("\n=== ENSEMBLE (logistic regression, 5-fold CV) ===")
    rho_ens, _ = spearmanr(proba, ranks)
    auc_ens = roc_auc_score(yn_labels, [proba[i] for i in yn_idx])
    print(f"  ensemble  rho={rho_ens:+.3f}  AUROC={auc_ens:.3f}")

    # 5) Bucket means under ensemble score
    by = {"y": [], "b": [], "n": []}
    for r, s in zip(rows, proba):
        by[r["rating"]].append(float(s))
    print("\nMean ensemble score by rating:")
    for k in ("y", "b", "n"):
        print(f"  {k}: n={len(by[k])} mean={mean(by[k]):.3f}")

    # 6) Calibrated thresholds: at given precision, what fraction passes?
    print("\nThreshold sweep (held-out 246 rows, ensemble):")
    print(f"  {'thresh':>7s}  {'pass%':>6s}  {'precision':>9s}  {'recall_y':>9s}")
    for t in (0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9):
        passing = [(s, r["rating"]) for s, r in zip(proba, rows) if s >= t]
        if not passing:
            continue
        pass_pct = 100 * len(passing) / len(rows)
        n_y = sum(1 for _, r in passing if r == "y")
        precision = n_y / len(passing)
        total_y = sum(1 for r in rows if r["rating"] == "y")
        recall = n_y / total_y if total_y else 0
        print(f"  {t:>7.2f}  {pass_pct:>5.1f}%  {precision:>9.3f}  {recall:>9.3f}")

    # 7) Train final clf on ALL data, save weights
    clf.fit(X, y_bin)
    print(f"\nFinal feature weights: f_sem={clf.coef_[0,0]:+.3f} "
          f"f_val={clf.coef_[0,1]:+.3f} f_len={clf.coef_[0,2]:+.3f} "
          f"f_noref={clf.coef_[0,3]:+.3f}  (intercept {clf.intercept_[0]:+.3f})")


if __name__ == "__main__":
    main()
