#!/usr/bin/env python3
"""Theme 4 (self-reference re-strengthening) DPO addition.

iter14's 1108-train corpus diluted iter12's stem-leak/self-reference
signal (iter12 was 180 of 308 pairs = 58%; iter14 is 180 of 1108 = 16%).
Three iter14 verbs flipped y → self-reference (réinstaller, étouffer,
peser → emit the lemma itself as the clue).

This addition pairs `chosen=non-overlapping synonym` with `rejected=lemma
itself`, explicitly reinforcing "don't echo the prompt." 12 pairs total
(10 train + 1 valid + 1 test).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

# (lemma, rejected_self, chosen_synonym)
PAIRS: list[tuple[str, str, str]] = [
    # observed iter14 self-ref regressions
    ("réinstaller", "Réinstaller", "Replacer"),
    ("étouffer",    "Étouffer",    "Suffoquer"),
    ("peser",       "Peser lourd", "Évaluer"),
    # broader at-risk verbs (verbs whose lemma is short enough that
    # the model might emit it verbatim as a clue):
    ("manger",      "Manger",       "Avaler"),
    ("voir",        "Voir",         "Apercevoir"),
    ("dire",        "Dire",         "Énoncer"),
    ("faire",       "Faire",        "Exécuter"),
    ("aller",       "Aller",        "Marcher"),
    ("écrire",      "Écrire",       "Rédiger"),
    ("courir",      "Courir",       "Galoper"),
    # valid + test
    ("crier",       "Crier",        "Hurler"),
    ("pleurer",     "Pleurer",      "Sangloter"),
]

PROMPT = "Génère une définition mots-fléchés courte pour: {} [verbe]"


def main() -> int:
    here = Path(__file__).parent
    splits = {"train": PAIRS[:10], "valid": PAIRS[10:11], "test": PAIRS[11:12]}
    for name, items in splits.items():
        out = here / f"{name}.jsonl"
        with out.open("w", encoding="utf-8") as fh:
            for lemma, rejected, chosen in items:
                fh.write(json.dumps({
                    "prompt": PROMPT.format(lemma.upper()),
                    "chosen": chosen,
                    "rejected": rejected,
                }, ensure_ascii=False) + "\n")
        print(f"{name}.jsonl: {len(items)} pairs", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
