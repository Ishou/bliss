#!/usr/bin/env python3
"""Annotate the `pos` column on the lemma-clues raw CSV.

Reads `data/eval/production/lemma_clues_raw.csv`, computes each lemma's
dominant POS class via grammalecte total-occurrences, and writes the
result to `lemma_clues_raw_pos.csv`. POS classes: nom, verbe, adj, adv.
Ties broken by precedence nom > adj > adv > verbe (nouns give cleaner
clues; verbs conjugate well from a sibling, so they lose surface ties).
"""

from __future__ import annotations

import argparse
import csv
import os
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts" / "eval"))
from morphology_index import MorphologyIndex  # noqa: E402

POS_PRECEDENCE = {"nom": 0, "adj": 1, "adv": 2, "verbe": 3}


def lemma_pos_freq(lexique: Path) -> dict[tuple[str, str], int]:
    out: dict[tuple[str, str], int] = defaultdict(int)
    with lexique.open(encoding="utf-8") as f:
        seen_header = False
        l_idx = e_idx = t_idx = -1
        for line in f:
            if line.startswith("#"):
                continue
            cols = line.rstrip("\n").split("\t")
            if not seen_header:
                if cols[:1] == ["id"] and "Lemme" in cols and "Total occurrences" in cols:
                    l_idx = cols.index("Lemme")
                    e_idx = cols.index("Étiquettes")
                    t_idx = cols.index("Total occurrences")
                    seen_header = True
                continue
            if len(cols) <= max(l_idx, e_idx, t_idx):
                continue
            tags = cols[e_idx].split()
            # Match _classify precedence: verbe > nom > adj > adv. Grammalecte
            # tags both real nouns (chien, tracteur) and substantivizable adjs
            # (humain, petit) with `nom`; the absence of `nom` distinguishes
            # pure adjectives (agricole, barbant) from nominal lemmas.
            pos_class = ""
            for t in tags:
                if t.startswith("v") and len(t) > 1 and t[1].isdigit():
                    pos_class = "verbe"
                    break
            if not pos_class:
                if "nom" in tags:
                    pos_class = "nom"
                elif "adj" in tags:
                    pos_class = "adj"
                elif "adv" in tags:
                    pos_class = "adv"
            if not pos_class:
                continue
            try:
                freq = int(cols[t_idx])
            except ValueError:
                continue
            out[(cols[l_idx].lower(), pos_class)] += freq
    return out


def dominant_pos(lemma: str, freq: dict[tuple[str, str], int],
                 index: MorphologyIndex) -> str:
    classes = index.pos_classes_of_form(lemma)
    candidates = [(c, freq.get((lemma, c), 0)) for c in classes if c in POS_PRECEDENCE]
    if not candidates:
        return ""
    max_f = max(f for _, f in candidates)
    tied = [c for c, f in candidates if f == max_f]
    tied.sort(key=lambda c: POS_PRECEDENCE[c])
    return tied[0]


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--src", type=Path,
                   default=REPO / "data" / "eval" / "production" / "lemma_clues_raw.csv")
    p.add_argument("--dst", type=Path,
                   default=REPO / "data" / "eval" / "production" / "lemma_clues_raw_pos.csv")
    p.add_argument("--lexique", type=Path,
                   default=Path(os.path.expanduser(
                       "~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt")))
    args = p.parse_args()

    print("loading morphology index...", file=sys.stderr)
    index = MorphologyIndex.load(args.lexique)
    print("computing lemma-pos frequencies...", file=sys.stderr)
    freq = lemma_pos_freq(args.lexique)

    with args.src.open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
        fieldnames = list(rows[0].keys())
    print(f"loaded {len(rows)} rows", file=sys.stderr)

    by_class: dict[str, int] = defaultdict(int)
    unresolved = 0
    for r in rows:
        pos = dominant_pos(r["lemma"], freq, index)
        if not pos:
            unresolved += 1
        r["pos"] = pos
        by_class[pos or "(unresolved)"] += 1

    with args.dst.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, "") for k in fieldnames})

    print(f"\nwrote {args.dst}")
    print(f"  POS distribution: {dict(by_class)}")
    print(f"  unresolved: {unresolved}")


if __name__ == "__main__":
    main()
