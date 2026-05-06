#!/usr/bin/env python3
"""Theme 2 (POS adherence) DPO addition for iter13.2.

iter13.1's small-corpus DPO continuation drifted some verb prompts toward
noun-shape outputs (`bouger → "Changements d'emplacement"`, `ratifier →
"Approbation formelle"`, `traverser → "Franc-tireur"`). The validator
catches these as `pos-mismatch` and routes them to placeholder, so they
don't ship — but coverage drops.

Each pair below targets the verb-vs-noun POS adherence: rejected = noun
or noun-phrase emission for a verb prompt; chosen = clean infinitive
verb synonym. Both must pass `validate_lemma_clue` AND avoid the 5-char
stem-leak rule against the lemma.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

# (lemma, rejected_noun_or_noun_phrase, chosen_verb_infinitive)
PAIRS: list[tuple[str, str, str]] = [
    # observed iter13.1 regressions:
    ("ratifier",  "Approbation formelle",     "Confirmer"),
    ("traverser", "Franc-tireur",             "Franchir"),
    ("bouger",    "Changements d'emplacement","Déplacer"),
    ("vouer",     "Engagement total",         "Dédier"),
    # synthesized cases on the same theme (broaden the signal beyond just
    # the four observed verbs; covers POS-productive verbs whose nominal
    # form is a likely DPO drift target):
    ("effacer",   "Suppression définitive",   "Rayer"),
    ("brûler",    "Combustion totale",        "Calciner"),
    ("limiter",   "Borne fixe",               "Cantonner"),
    ("fixer",     "Position figée",           "Arrimer"),
    # valid + test:
    ("attribuer", "Distribution",             "Octroyer"),
    ("décider",   "Choix tranché",            "Statuer"),
]

PROMPT_TEMPLATE = "Génère une définition mots-fléchés courte pour: {} [verbe]"


def main() -> int:
    here = Path(__file__).parent
    splits = {"train": PAIRS[:8], "valid": PAIRS[8:9], "test": PAIRS[9:10]}
    for name, items in splits.items():
        out = here / f"{name}.jsonl"
        with out.open("w", encoding="utf-8") as fh:
            for lemma, rejected, chosen in items:
                fh.write(json.dumps({
                    "prompt": PROMPT_TEMPLATE.format(lemma.upper()),
                    "chosen": chosen,
                    "rejected": rejected,
                }, ensure_ascii=False) + "\n")
        print(f"{name}.jsonl: {len(items)} pairs", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
