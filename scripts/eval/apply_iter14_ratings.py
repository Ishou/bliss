#!/usr/bin/env python3
"""Apply self-ratings to iter14_pp_eval.csv. Re-uses iter13.2 ratings where
the row didn't change shape; re-rates the 50 flipped verbs."""
from __future__ import annotations

import csv, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from apply_iter13_2_ratings import RATINGS as ITER13_2_RATINGS

OVERRIDES: dict[str, str] = {
    # gains
    "boucher":     "y",  # pos-mismatch -> Égorger
    "grouper":     "y",  # pos-mismatch -> Rassembler
    "éclore":      "y",  # pos-mismatch -> Émerger de l'œuf
    "briller":     "y",  # n -> Éclater de lumière (sense-disambiguated)
    "tomber":      "y",  # n -> Chuter
    "exister":     "y",  # n -> Être présent et réel
    "innover":     "y",  # placeholder -> Créer quelque chose de nouveau
    "recourir":    "y",  # b -> Solliciter
    "exploiter":   "y",  # b -> Tirer profit de (de added!)
    "déboucher":   "y",  # b -> Ouvrir largement
    "communier":   "",   # placeholder both
    # neutral or marginal flip
    "abandonner":  "y",  # Quitter
    "accomplir":   "y",
    "accoucher":   "y",
    "affecter":    "y",
    "cantonner":   "y",  # b -> "Assigner à un lieu précis" (better)
    "confisquer":  "b",  # y -> "Saisir et garder" (compound, second verb)
    "conférer":    "y",
    "conjurer":    "b",  # b -> Évincer (different but borderline)
    "décomposer":  "b",  # y -> Écarter en parties (weaker)
    "dériver":     "y",  # b -> "Provenir d'une source"
    "dévorer":     "y",
    "embarquer":   "y",  # n -> "Monter à bord" (was "Prise place" n)
    "frire":       "y",  # similar
    "frémir":      "b",
    "guider":      "y",
    "généraliser": "y",
    "gérer":       "y",
    "maudire":     "y",  # close
    "mourir":      "y",  # Trépasser cleaner
    "mouvoir":     "y",
    "promener":    "y",
    "propager":    "y",
    "relever":     "y",
    "retrouver":   "y",
    "rompre":      "y",
    "ruiner":      "y",
    "surmonter":   "b",  # y -> "Vaincre et dominer" (compound)
    "visualiser":  "y",
    "vulgariser":  "y",
    "équiper":     "y",  # b -> "Munir" single-word
    # losses (rated -> placeholder)
    "commenter":   "",   # y -> pp-skipped
    "concerner":   "",   # pos-mismatch both
    "déférer":     "n",  # y -> "Remettre à plus tard" (wrong sense, regression)
    "hypothéquer": "",   # y -> pp-only-skipped
    "schématiser": "",   # y -> pp-only-skipped
    "réinstaller": "",   # y -> self-reference
    "étouffer":    "",   # y -> self-reference
    "peser":       "",   # placeholder both (different shape)
}


def main() -> int:
    src = Path("data/eval/iter14_pp_eval.csv")
    rows = list(csv.DictReader(src.open(encoding="utf-8")))
    rated = placeholder = 0
    for r in rows:
        if (r["validation_flag"] == "ok"
                and r["inflection_status"] == "inflected"):
            r["rating"] = OVERRIDES.get(r["lemma"], ITER13_2_RATINGS.get(r["lemma"], ""))
            if r["rating"]:
                rated += 1
        else:
            r["rating"] = ""
            placeholder += 1

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
    print(f"rated rows: {rated}/{len(rows)}; placeholder: {placeholder}")
    print(f"y={counts['y']}, b={counts['b']}, n={counts['n']}")
    print(f"acceptance: {score/n:.3f} ({score}/{n})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
