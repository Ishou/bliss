#!/usr/bin/env python3
"""Build training pairs for the DBnary filter model.

Filter task: given (lemma, candidate_text), score how well the
candidate matches the lemma. Trained with contrastive loss against
DBnary senses.

Output (data/lora_filter/):
    train.jsonl, valid.jsonl, test.jsonl
        Each line: {"anchor": "<lemma>", "positive": "<def>"}
        Negatives are in-batch random (MultipleNegativesRankingLoss).
    eval_human.jsonl
        Held-out human-rated rows from iter3-iter9 with scores:
        {"lemma": "<lemma>", "candidate": "<clue>", "rating": "y|b|n"}

License: per ADR-0023, model weights trained on DBnary are
non-redistributive: the filter outputs scores, not DBnary text.
"""

from __future__ import annotations

import csv
import json
import random
import re
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
DBNARY_CSV = REPO / "data" / "dbnary" / "dbnary_fr.csv"
OUT = REPO / "data" / "lora_filter"
SEED = 20260504

# Drop senses with archaic/disused markers — they pollute training and
# reflect non-modern usage we don't want the filter to prefer.
_ARCHAIC = {"vieilli", "désuet", "vieux", "archaïsme", "archaïque", "anciennement"}
_LEADING_TAG = re.compile(r"^\s*\(([^)]+)\)")


def is_archaic(sense: str) -> bool:
    s = sense.lstrip()
    while True:
        m = _LEADING_TAG.match(s)
        if not m:
            return False
        if any(k in m.group(1).lower() for k in _ARCHAIC):
            return True
        s = s[m.end():].lstrip()


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    rng = random.Random(SEED)

    # 1) Build (lemma, sense) positive pairs
    pos_pairs: list[tuple[str, str]] = []
    with DBNARY_CSV.open(encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            lemma = (r.get("lemma") or "").strip().lower()
            defs = (r.get("definition") or "").strip()
            if not lemma or not defs:
                continue
            for sense in defs.split("|"):
                sense = sense.strip()
                if not sense or len(sense) < 3 or len(sense) > 250:
                    continue
                if is_archaic(sense):
                    continue
                pos_pairs.append((lemma, sense))

    print(f"raw (lemma, sense) positives: {len(pos_pairs):,}")

    # 2) Held-out human-rated rows (for eval only — never seen during training)
    eval_rows: list[dict] = []
    for iter_path in sorted((REPO / "data" / "eval").glob("lemma_clues_iter*.csv")):
        if not iter_path.name.startswith("lemma_clues_iter"):
            continue
        with iter_path.open(encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f):
                rating = (row.get("rating") or "").strip().lower()
                if rating not in ("y", "b", "n"):
                    continue
                eval_rows.append({
                    "lemma": (row.get("lemma") or "").strip().lower(),
                    "candidate": (row.get("lemma_clue") or "").strip(),
                    "rating": rating,
                })

    print(f"eval rows (human-rated): {len(eval_rows)}")

    # 3) Eval set: hold out 30% of rated rows by lemma; rest are training
    #    positives so the filter learns mots-fléchés clue style, not just
    #    DBnary definition style.
    rng_eval = random.Random(SEED ^ 0xBEEF)
    eval_lemma_pool = sorted({e["lemma"] for e in eval_rows})
    rng_eval.shuffle(eval_lemma_pool)
    n_eval_lemmas = max(60, int(len(eval_lemma_pool) * 0.3))
    eval_lemmas = set(eval_lemma_pool[:n_eval_lemmas])

    # Move y-rated rows whose lemma is NOT held out into the training
    # positives (b/n still excluded; positives only).
    iter_y_pairs = []
    for r in eval_rows:
        if r["lemma"] in eval_lemmas:
            continue
        if r["rating"] == "y":
            iter_y_pairs.append((r["lemma"], r["candidate"]))

    # Final eval rows: only those whose lemma is in eval_lemmas
    eval_rows = [e for e in eval_rows if e["lemma"] in eval_lemmas]

    # Filter DBnary positives to non-held-out lemmas
    pos_pairs = [p for p in pos_pairs if p[0] not in eval_lemmas]
    print(f"DBnary positives after eval-lemma exclusion: {len(pos_pairs):,}")
    print(f"iter y-rated training positives:               {len(iter_y_pairs):,}")
    print(f"held-out eval lemmas:                          {len(eval_lemmas)}")
    print(f"final eval rows:                                {len(eval_rows)}")
    pos_pairs = pos_pairs + iter_y_pairs

    # 4) Shuffle + split: 95/2.5/2.5
    rng.shuffle(pos_pairs)
    n = len(pos_pairs)
    n_train = int(n * 0.95)
    n_valid = int(n * 0.025)
    train = pos_pairs[:n_train]
    valid = pos_pairs[n_train:n_train + n_valid]
    test = pos_pairs[n_train + n_valid:]
    print(f"train={len(train):,} valid={len(valid):,} test={len(test):,}")

    def write(path: Path, rows: list[tuple[str, str]]) -> None:
        with path.open("w", encoding="utf-8") as f:
            for lemma, sense in rows:
                f.write(json.dumps({"anchor": lemma, "positive": sense},
                                   ensure_ascii=False) + "\n")

    write(OUT / "train.jsonl", train)
    write(OUT / "valid.jsonl", valid)
    write(OUT / "test.jsonl", test)

    with (OUT / "eval_human.jsonl").open("w", encoding="utf-8") as f:
        for r in eval_rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    print(f"\nwrote {OUT}/")


if __name__ == "__main__":
    main()
