#!/usr/bin/env python3
"""Apply tightened homograph replacements to the POS-annotated corpus.

Gate: replace only if
  - new_flag == "ok"
  - score_delta > 0.03
  - new_clue != old_clue
  - token-set Jaccard(old_tokens, new_tokens) <= 0.7  (drops near-rewrites)
"""
from __future__ import annotations
import argparse, csv, re
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
TOKEN = re.compile(r"[a-zàâäæçéèêëîïôöœùûüÿ']+", re.IGNORECASE)


def jaccard(a: str, b: str) -> float:
    sa = {t.lower() for t in TOKEN.findall(a)}
    sb = {t.lower() for t in TOKEN.findall(b)}
    if not sa or not sb:
        return 0.0
    return len(sa & sb) / len(sa | sb)


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--src", type=Path,
                   default=REPO / "data" / "eval" / "production" / "lemma_clues_raw_pos.csv")
    p.add_argument("--diff", type=Path,
                   default=REPO / "data" / "eval" / "production" / "homograph_fix.csv")
    p.add_argument("--dst", type=Path,
                   default=REPO / "data" / "eval" / "production" / "lemma_clues_raw_pos_fixed.csv")
    p.add_argument("--min-delta", type=float, default=0.03)
    p.add_argument("--max-jaccard", type=float, default=0.7)
    args = p.parse_args()

    with args.src.open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
        fieldnames = list(rows[0].keys())
    by_lemma = {r["lemma"]: r for r in rows}

    replaced = 0
    rejected_delta = 0
    rejected_flag = 0
    rejected_jaccard = 0
    with args.diff.open(encoding="utf-8", newline="") as f:
        for d in csv.DictReader(f):
            lemma = d["lemma"]
            if lemma not in by_lemma:
                continue
            new = d["new_clue"]
            old = d["old_clue"]
            if not new or new == old:
                continue
            if d["new_flag"] != "ok":
                rejected_flag += 1
                continue
            try:
                delta = float(d["score_delta"])
            except ValueError:
                continue
            if delta <= args.min_delta:
                rejected_delta += 1
                continue
            if jaccard(old, new) > args.max_jaccard:
                rejected_jaccard += 1
                continue
            r = by_lemma[lemma]
            r["lemma_clue"] = new
            r["filter_score"] = f"{float(d['new_score']):.4f}"
            r["validation_flag"] = d["new_flag"]
            replaced += 1

    with args.dst.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, "") for k in fieldnames})

    print(f"wrote {args.dst}")
    print(f"  replaced: {replaced}")
    print(f"  rejected (flag != ok): {rejected_flag}")
    print(f"  rejected (delta <= {args.min_delta}): {rejected_delta}")
    print(f"  rejected (jaccard > {args.max_jaccard}): {rejected_jaccard}")


if __name__ == "__main__":
    main()
