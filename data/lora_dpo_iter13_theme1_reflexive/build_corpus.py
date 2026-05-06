#!/usr/bin/env python3
"""Theme 1 (reflexive) DPO addition for iter13.1.

Targets verbs where the iter13 model emits a reflexive infinitive
(`S'élever`, `Se déplacer`) as the lemma clue. PP-inflating a reflexive
verb yields ungrammatical state-clue text (`*S'élevée`, `*Se déplacée` —
the pronoun stranded against a participle is not a French adjectival
reading).

Every pair below has chosen=non-reflexive infinitive synonym (PP-inflects
cleanly) and rejected=reflexive infinitive (the bad shape iter13 currently
emits for some PP-productive verbs).

8 train + 1 valid + 1 test = 10 pairs total.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

PAIRS: list[tuple[str, str, str]] = [
    # (lemma, rejected_reflexive, chosen_non_reflexive)
    # the two n's directly observed in iter13's first eval:
    ("relever",   "S'élever",     "Soulever"),
    ("mouvoir",   "Se déplacer",  "Bouger"),
    # broader set on the same theme:
    ("déplacer",  "Se mouvoir",   "Bouger"),
    ("baigner",   "Se baigner",   "Tremper"),
    ("coucher",   "Se coucher",   "Étendre"),
    ("réveiller", "Se réveiller", "Éveiller"),
    ("cacher",    "Se cacher",    "Dissimuler"),
    ("tourner",   "Se tourner",   "Pivoter"),
    # valid + test:
    ("évanouir",  "S'évanouir",   "Pâmer"),
    ("enfuir",    "S'enfuir",     "Fuir"),
]

PROMPT_TEMPLATE = "Génère une définition mots-fléchés courte pour: {} [verbe]"


def main() -> int:
    here = Path(__file__).parent
    splits = {
        "train": PAIRS[:8],
        "valid": PAIRS[8:9],
        "test": PAIRS[9:10],
    }
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
