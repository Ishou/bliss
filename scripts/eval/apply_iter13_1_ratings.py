#!/usr/bin/env python3
"""Apply ratings to iter13_1_pp_eval.csv. Re-uses iter13 ratings for verbs
that didn't change shape; rates the 35 flipped verbs fresh.
"""
from __future__ import annotations

import csv
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from apply_iter13_ratings import RATINGS as ITER13_RATINGS

# Fresh ratings for the 35 verbs whose lemma_clue/flag/status changed in
# iter13.1. Same y/b/n scale; rated by reading the inflected_clue against
# the pp_surface in context. Verbs whose row remained inflected+ok carry
# their iter13 rating forward (the lemma_clue text may differ but the
# structural shape is the same — we re-rate where the *shape* shifted).
ITER13_1_OVERRIDES: dict[str, str] = {
    # gains
    "mouvoir":      "y",  # n->y: Se déplacer -> Déplacer légèrement
    "relever":      "y",  # n->y: S'élever -> Ascensionner
    "succéder":     "y",  # n->y: Suivre (gender bug) -> Remplacer
    "tomber":       "y",  # n->y: Déchiffrer -> Échouer
    "convenir":     "b",  # n->b: Correspondre -> Harmoniser
    "confisquer":   "y",  # b->y: Saisir et garder -> Saisir
    "dériver":      "y",  # b->y: Provenir -> Tirer origine de
    "superposer":   "y",  # b->y: Couvrir partiellement -> Recouvrir
    "peser":        "b",  # placeholder->b: pp-skip -> Étudier attentivement
    "communier":    "y",  # placeholder->y: pp-skip -> Partager
    # neutral (rating unchanged)
    "accomplir":    "y",  # y->y: Exécuter -> Réaliser
    "anéantir":     "y",  # y->y: Rayer de la surface ... -> Rayer
    "concrétiser":  "y",  # y->y: Matérialiser -> Rendre réel et tangible
    "déboucher":    "y",  # y->y: Libérer -> Ouvrir largement
    "déférer":      "n",  # n->n: Remettre à plus tard -> Reporter à plus tard
    "goûter":       "y",  # y->y
    "insister":     "b",  # b->b
    "promener":     "y",  # y->y: Conduire en balade -> Conduire
    "propager":     "y",  # y->y: Diffuser largement -> Diffuser
    "trancher":     "y",  # y->y: Couper net -> Scinder
    "vulgariser":   "y",  # y->y: Rendre accessible -> Rendre commun et accessible
    "évaporer":     "y",  # y->y: Disperser en vapeur -> Disperser
    # losses (rated row → placeholder; rating becomes empty / not counted)
    "bouger":       "",   # y -> head-not-lemma (placeholder)
    "maudire":      "",   # y -> pp-only-skipped (placeholder)
    "ratifier":     "",   # b -> pos-mismatch (placeholder)
    "schématiser":  "",   # y -> unknown-head (placeholder)
    "traverser":    "",   # y -> pos-mismatch (placeholder)
    "vouer":        "",   # y -> unknown-head (placeholder)
    "éclore":       "",   # b -> pos-mismatch (placeholder)
    # losses (rated, but degraded)
    "décomposer":   "b",  # y -> b: Analyser en éléments simples -> Écarter en parties
    "munir":        "b",  # y -> b: Équiper -> Équiper et fournir (second verb fails)
    "reporter":     "b",  # y -> b: Transmettre -> Réciter
    # already broken (still broken / different broken)
    "boucher":      "",   # pos-mismatch both iters
    "recourir":     "",   # n in iter13 → unknown-head in iter13.1; placeholder either way
    "signaler":     "",   # unknown-head both iters
}


def main() -> int:
    src = Path("data/eval/iter13_1_pp_eval.csv")
    rows = list(csv.DictReader(src.open(encoding="utf-8")))

    rated_count = 0
    placeholder_count = 0
    for r in rows:
        if (r["validation_flag"] == "ok"
                and r["inflection_status"] == "inflected"):
            lemma = r["lemma"]
            if lemma in ITER13_1_OVERRIDES:
                r["rating"] = ITER13_1_OVERRIDES[lemma]
            else:
                r["rating"] = ITER13_RATINGS.get(lemma, "")
            if r["rating"]:
                rated_count += 1
        else:
            r["rating"] = ""
            placeholder_count += 1

    fieldnames = ["lemma", "lemma_clue", "validation_flag", "pp_surface",
                  "inflected_clue", "inflection_status", "rating"]
    with src.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=fieldnames, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)

    score_map = {"y": 1.0, "b": 0.5, "n": 0.0}
    counts = {"y": 0, "b": 0, "n": 0}
    score = 0.0
    for r in rows:
        if r["rating"] in score_map:
            counts[r["rating"]] += 1
            score += score_map[r["rating"]]
    n = sum(counts.values())
    acceptance = score / n if n else 0.0

    print(f"rated rows:    {rated_count}/{len(rows)}")
    print(f"placeholder:   {placeholder_count}")
    print(f"y={counts['y']}, b={counts['b']}, n={counts['n']}")
    print(f"acceptance:    {acceptance:.3f} ({score}/{n})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
