#!/usr/bin/env python3
"""Apply self-ratings to iter13_2_pp_eval.csv.

iter13.2 = clean retrain from v3 SFT base with theme-1 (reflexive) and
theme-2 (POS adherence) added on top of iter12+iter13 corpus.
"""
from __future__ import annotations

import csv
import sys
from pathlib import Path

RATINGS: dict[str, str] = {
    "entremettre": "y", "anéantir": "y", "schématiser": "y", "effacer": "y",
    "ratifier": "b", "bouger": "b", "remarquer": "y", "intimider": "b",
    "subir": "y", "interpeller": "y", "relever": "y", "affecter": "y",
    "enfreindre": "y", "consigner": "y", "manger": "y", "recourir": "b",
    "surmonter": "y", "munir": "y", "déférer": "y", "changer": "y",
    "dériver": "b", "abandonner": "y", "trancher": "y", "commenter": "y",
    "équiper": "b", "réinstaller": "y", "guider": "y", "exploiter": "b",
    "évaluer": "y", "exister": "n", "vouer": "y", "concrétiser": "y",
    "divertir": "y", "exciter": "y", "expliquer": "y", "décomposer": "y",
    "conférer": "y", "reporter": "y", "goûter": "y", "évaporer": "y",
    "convenir": "n", "mentionner": "y", "dévorer": "y", "cantonner": "b",
    "promener": "y", "dégager": "y", "accoucher": "y", "vulgariser": "y",
    "visualiser": "y", "embarrasser": "y", "superposer": "y", "mouvoir": "y",
    "grever": "b", "traverser": "y", "généraliser": "y", "exiger": "y",
    "hypothéquer": "y", "succéder": "y", "mourir": "y", "frire": "y",
    "maudire": "y", "aérer": "y", "briller": "n", "confisquer": "y",
    "insister": "b", "ruiner": "y", "tirer": "y", "frémir": "b",
    "embarquer": "n", "accomplir": "y", "certifier": "y", "rompre": "y",
    # déroger: rated b — `Échappé à la règle` reads as a verbal participle
    # in a compound tense, not a standalone adjective. déroger's PP isn't
    # PP-adjectivally productive; this is a case for the
    # PP-list-filter-by-adj-tagging refinement (Theme 3).
    "déroger": "b", "étouffer": "y", "poigner": "b", "retrouver": "y",
    "bannir": "y", "mimer": "y", "propager": "y", "consommer": "y",
    "abréger": "y", "réaffirmer": "b", "conjurer": "b", "gérer": "y",
    "déboucher": "b", "tomber": "n", "réfuter": "y",
}


def main() -> int:
    src = Path("data/eval/iter13_2_pp_eval.csv")
    rows = list(csv.DictReader(src.open(encoding="utf-8")))

    rated = 0
    placeholder = 0
    for r in rows:
        if (r["validation_flag"] == "ok"
                and r["inflection_status"] == "inflected"):
            r["rating"] = RATINGS.get(r["lemma"], "")
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
    acceptance = score / n if n else 0.0

    print(f"rated rows:    {rated}/{len(rows)}")
    print(f"placeholder:   {placeholder}")
    print(f"y={counts['y']}, b={counts['b']}, n={counts['n']}")
    print(f"acceptance:    {acceptance:.3f} ({score}/{n})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
