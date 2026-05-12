#!/usr/bin/env python3
"""Stitch iter19's shipped clues with iter18's as a fallback.

Rationale
---------
iter19 (v11/iter-1100) ships only 5,371 lemmas vs iter18's 7,525, due to
DPO-induced length collapse on a subset of the corpus (`abaissée →
"Rédurée"`, `aboutir → "At"`, etc — model emits a few tokens then EOS).
iter19's quality wins (no English leaks, cane fix, chien/voiture
disambiguation) are real on the lemmas it does ship.

This script combines:
  • iter19 shipped (preferred) — gets the iter19 quality lift
  • iter18 shipped fallback (where iter19 dropped/regressed)

Output is structurally identical to lemma_clues_shipped.csv with an
added `source_iter` column ("iter19" or "iter18-fallback") so downstream
can audit the provenance.

INPUTS:
  data/eval/production/lemma_clues_shipped.csv               (iter19, primary)
  data/eval/production/iter18_baseline/lemma_clues_shipped.csv  (iter18, fallback)

OUTPUT:
  data/eval/production/lemma_clues_shipped_stitched.csv
"""
from __future__ import annotations
import csv, os, sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts" / "clue_generation"))
PROD = REPO / "data" / "eval" / "production"

ITER19_SHIPPED = PROD / "lemma_clues_shipped.csv"
ITER18_SHIPPED = PROD / "iter18_baseline" / "lemma_clues_shipped.csv"
OUTPUT         = PROD / "lemma_clues_shipped_stitched.csv"

# Grammalecte POS-precedence (mirrors build_surface_clues.py / phase 1
# sampler): when a lemma has multiple POS roles, pick the highest-freq
# entry in this order. We use this to backfill `pos` on iter18 rows.
LEX_PATH = Path(os.environ.get(
    "GRAMMALECTE_LEX",
    os.path.expanduser("~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt"),
))


def load_shipped(path: Path) -> dict[str, dict]:
    """lemma → row mapping."""
    out: dict[str, dict] = {}
    with path.open(encoding="utf-8") as f:
        for r in csv.DictReader(f):
            out[r["lemma"]] = r
    return out


def _resolve_pos_per_lemma() -> dict[str, str]:
    """For every lemma in grammalecte, pick the highest-freq POS that
    build_surface_clues recognises ({nom, adj, verbe, adv}). Mirrors
    phase 1 sampler. Used to backfill `pos` on iter18 rows whose CSV
    predates the [pos]-tagged prompt schema."""
    from build_surface_clues import POS_PRECEDENCE, lemma_pos_freq
    print(f"loading lemma_pos_freq from {LEX_PATH.name}…", file=sys.stderr)
    lp = lemma_pos_freq(LEX_PATH)
    best_pos: dict[str, str] = {}
    best_freq: dict[str, int] = {}
    for (lemma, pos), freq in lp.items():
        if pos not in POS_PRECEDENCE:
            continue
        if freq > best_freq.get(lemma, -1):
            best_freq[lemma] = freq
            best_pos[lemma] = pos
    print(f"  resolved POS for {len(best_pos)} lemmas", file=sys.stderr)
    return best_pos


def main() -> None:
    iter19 = load_shipped(ITER19_SHIPPED)
    iter18 = load_shipped(ITER18_SHIPPED)
    print(f"iter19 shipped: {len(iter19)}", file=sys.stderr)
    print(f"iter18 shipped: {len(iter18)}", file=sys.stderr)

    # iter18 baseline rows lack the `pos` column (CSV predates [pos]-tagged
    # prompts). Backfill via grammalecte's dominant-POS lookup so
    # build_surface_clues can key rows by (lemma, pos).
    needs_pos = [lemma for lemma, r in iter18.items()
                 if not r.get("pos") and lemma not in iter19]
    print(f"iter18 rows needing pos backfill: {len(needs_pos)}", file=sys.stderr)
    if needs_pos:
        best_pos = _resolve_pos_per_lemma()
        filled = 0
        for lemma in needs_pos:
            p = best_pos.get(lemma)
            if p:
                iter18[lemma]["pos"] = p
                filled += 1
        print(f"  backfilled {filled}/{len(needs_pos)} "
              f"({(len(needs_pos)-filled)} unresolved — left blank)",
              file=sys.stderr)

    all_lemmas = set(iter19) | set(iter18)
    stitched: list[dict] = []
    from_19 = 0
    from_18 = 0
    for lemma in sorted(all_lemmas):
        if lemma in iter19:
            row = dict(iter19[lemma])
            row["source_iter"] = "iter19"
            from_19 += 1
        else:
            row = dict(iter18[lemma])
            row["source_iter"] = "iter18-fallback"
            from_18 += 1
        stitched.append(row)

    # Write with stable column order (iter19's columns first, then source_iter)
    fieldnames = list(next(iter(iter19.values())).keys()) + ["source_iter"]
    with OUTPUT.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        w.writeheader()
        for r in stitched:
            w.writerow({k: r.get(k, "") for k in fieldnames})

    print(f"\nwrote {OUTPUT}", file=sys.stderr)
    print(f"  total shipped (stitched): {len(stitched)}", file=sys.stderr)
    print(f"  from iter19:           {from_19} ({from_19/len(stitched)*100:.1f}%)",
          file=sys.stderr)
    print(f"  from iter18-fallback:  {from_18} ({from_18/len(stitched)*100:.1f}%)",
          file=sys.stderr)
    print(f"  vs iter18 alone:       +{len(stitched)-len(iter18)} lemmas",
          file=sys.stderr)
    print(f"  vs iter19 alone:       +{len(stitched)-len(iter19)} lemmas",
          file=sys.stderr)


if __name__ == "__main__":
    main()
