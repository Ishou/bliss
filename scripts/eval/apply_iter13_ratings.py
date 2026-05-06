#!/usr/bin/env python3
"""Apply self-ratings to iter13_pp_eval.csv.

Ratings are encoded inline below; one entry per (lemma -> rating) for the
86 rows whose `inflection_status == 'inflected'`. Rows that fall to
pp-only-skipped or fail validation are excluded from the ratable set —
they ship as placeholder.
"""
from __future__ import annotations

import csv
import sys
from pathlib import Path

# Self-ratings produced by reading each inflected PP surface in context:
# does the inflated state-clue read as a French speaker's mots-fléchés
# definition for the surface form? y=accept, b=borderline, n=reject.
RATINGS: dict[str, str] = {
    # 86 inflected rows
    "entremettre": "y", "anéantir": "y", "schématiser": "y", "effacer": "y",
    "ratifier": "b", "bouger": "y", "remarquer": "y", "intimider": "b",
    "subir": "y", "interpeller": "y", "relever": "n", "affecter": "y",
    "enfreindre": "y", "consigner": "y", "manger": "y", "recourir": "n",
    "surmonter": "b", "munir": "y", "déférer": "n", "changer": "y",
    "dériver": "b", "abandonner": "y", "trancher": "y", "équiper": "b",
    "réinstaller": "y", "guider": "y", "exploiter": "b", "évaluer": "y",
    "exister": "n", "vouer": "y", "concrétiser": "y", "divertir": "y",
    "exciter": "y", "expliquer": "y", "décomposer": "y", "conférer": "y",
    "reporter": "y", "goûter": "y", "évaporer": "y", "convenir": "n",
    "mentionner": "y", "cantonner": "b", "promener": "y", "dégager": "y",
    "accoucher": "y", "vulgariser": "y", "visualiser": "y", "superposer": "b",
    "mouvoir": "n", "grever": "b", "éclore": "b", "traverser": "y",
    "généraliser": "y", "exiger": "y", "hypothéquer": "y", "succéder": "n",
    "mourir": "y", "frire": "y", "maudire": "y", "aérer": "y",
    "briller": "n", "confisquer": "b", "insister": "b", "ruiner": "y",
    "tirer": "y", "frémir": "b", "embarquer": "y", "accomplir": "y",
    "certifier": "y", "rompre": "y", "déroger": "n", "étouffer": "y",
    "poigner": "n", "retrouver": "y", "bannir": "y", "mimer": "y",
    "propager": "y", "consommer": "y", "grouper": "y", "abréger": "y",
    "réaffirmer": "b", "conjurer": "b", "gérer": "b", "déboucher": "y",
    "tomber": "n", "réfuter": "y",
}


def main() -> int:
    src = Path("data/eval/iter13_pp_eval.csv")
    rows = list(csv.DictReader(src.open(encoding="utf-8")))

    rated = 0
    placeholder = 0  # validation-failed OR pp-only-skipped — not rated
    for r in rows:
        if (r["validation_flag"] == "ok"
                and r["inflection_status"] == "inflected"):
            r["rating"] = RATINGS.get(r["lemma"], "")
            if r["rating"]:
                rated += 1
        else:
            placeholder += 1

    src.write_text(
        "",  # truncate before re-writing
    )
    fieldnames = ["lemma", "lemma_clue", "validation_flag", "pp_surface",
                  "inflected_clue", "inflection_status", "rating"]
    with src.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=fieldnames, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)

    # Compute acceptance over the 86 ratable rows (y=1, b=0.5, n=0).
    score_map = {"y": 1.0, "b": 0.5, "n": 0.0}
    counts = {"y": 0, "b": 0, "n": 0}
    score = 0.0
    for r in rows:
        if r["rating"] in score_map:
            counts[r["rating"]] += 1
            score += score_map[r["rating"]]
    n_rated = sum(counts.values())
    acceptance = score / n_rated if n_rated else 0.0

    print(f"rated rows:        {rated}/{len(rows)}")
    print(f"placeholder rows:  {placeholder} "
          f"(validation-failed + pp-only-skipped)")
    print(f"counts:            y={counts['y']}, b={counts['b']}, n={counts['n']}")
    print(f"acceptance (self): {acceptance:.3f} ({score}/{n_rated})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
