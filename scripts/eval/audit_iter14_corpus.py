#!/usr/bin/env python3
"""Audit the iter14 1000-pair corpus for miscategorized n_clues.

Tag each n_clue by the failure mode it actually represents (per the
inflater's framework):
  - DOBJ            verb + det + N    (the canonical bad foil — keeps)
  - REFLEXIVE       Se/S' head        (correct foil)
  - SELF_REF        rejected == lemma (correct foil)
  - POS_NOUN        noun phrase       (correct foil — POS-mismatch)
  - PLEONASM        "Mettre ensemble" (correct foil)
  - PP_BRIDGE       verb + de/à/en/dans/... + N  (MISCATEGORIZED — PP-friendly)
  - VERB_ADV        verb + comparative/manner adv (mostly OK; ambiguous)
  - VERB_VERB       verb + verb        (compound; second won't inflate; OK foil-ish)
  - SHORT_OK        2-3 tokens, non-DObj (likely OK clue, MISCATEGORIZED)
  - OTHER           everything else (review)

Output: data/lora_dpo_iter14_authored/audit.csv with category column.
"""
from __future__ import annotations

import csv
import json
import re
import sys
from pathlib import Path

# Inflater rules (mirrored verbatim from inflect_clue.py).
_DOBJ_DETERMINERS = {
    "le", "la", "les", "l", "un", "une", "des", "du",
    "ce", "cet", "cette", "ces",
    "son", "sa", "ses", "mon", "ma", "mes", "ton", "ta", "tes",
    "leur", "leurs", "notre", "nos", "votre", "vos",
}
_PP_BRIDGE_PREPS = {"de", "d", "à", "au", "aux", "en", "dans", "sur",
                    "sous", "par", "pour", "avec", "sans"}
_REFLEXIVE = {"se", "s"}

# Comparative/manner adverbs (small closed set; presence after a verb
# usually means "verb + adv" not "verb + DObj").
_ADV_HEAD = {"moins", "plus", "très", "trop", "peu", "bien", "mal",
             "vite", "lentement", "rapidement", "souvent", "tôt", "tard",
             "longtemps", "ensemble", "ailleurs", "loin", "près",
             "haut", "bas", "dehors", "dedans", "avant", "après"}

# Pleonasm patterns (mirroring validate_clue's _find_pleonasm).
_PLEONASM_PATTERNS = [
    r"\b(joindre|réunir|rassembler|associer|combiner|mêler) ensemble\b",
    r"\bmonter en haut\b",
    r"\bdescendre en bas\b",
    r"\bprévoir à l'avance\b",
    r"\bprévenir à l'avance\b",
    r"\bcollaborer ensemble\b",
    r"\bune fois encore\b",
]

_TOKEN_RE = re.compile(r"[\wÀ-ÿŒœŸ]+|[^\s\wÀ-ÿ]+", re.UNICODE)


def _tokens(s: str) -> list[str]:
    return _TOKEN_RE.findall(s.lower())


def _is_alpha(tok: str) -> bool:
    return bool(re.match(r"^[\wÀ-ÿŒœŸ]+$", tok))


def categorize(lemma: str, n_clue: str) -> str:
    s = n_clue.strip()
    s_lower = s.lower()

    # SELF_REF — rejected equals or contains lemma exactly.
    if s_lower == lemma or s_lower.startswith(lemma + " "):
        return "SELF_REF"

    # PLEONASM
    for pat in _PLEONASM_PATTERNS:
        if re.search(pat, s_lower):
            return "PLEONASM"

    toks = _tokens(s)
    alpha = [t for t in toks if _is_alpha(t)]

    # Empty / 1 token.
    if len(alpha) <= 1:
        return "SHORT_OK" if alpha else "OTHER"

    head = alpha[0]
    # REFLEXIVE
    if head in _REFLEXIVE:
        return "REFLEXIVE"

    # POS_NOUN — clue starts with a determiner (article/possessive)
    # without a verb head. e.g. "Un objet", "La pratique".
    if head in _DOBJ_DETERMINERS:
        return "POS_NOUN"

    # Now treat alpha[0] as head verb. Look at alpha[1].
    second = alpha[1]

    # PP_BRIDGE — verb + de/à/en/... → PP-friendly framing (MISCATEGORIZED).
    if second in _PP_BRIDGE_PREPS:
        return "PP_BRIDGE"

    # DOBJ — verb + determiner + (more)
    if second in _DOBJ_DETERMINERS:
        return "DOBJ"

    # VERB_ADV — verb + comparative/manner adverb
    if second in _ADV_HEAD:
        return "VERB_ADV"

    # VERB_VERB — verb + (presumed) verb (no det, no prep, no adv).
    # Also matches "ne pas X" etc; rough heuristic.
    return "VERB_VERB"


def main() -> int:
    src = Path("data/lora_dpo_iter14_authored/all.jsonl")
    out = Path("data/lora_dpo_iter14_authored/audit.csv")
    rows = []
    with src.open(encoding="utf-8") as f:
        for ln in f:
            r = json.loads(ln)
            lemma = r["prompt"].split(": ")[1].split(" [")[0].lower()
            cat = categorize(lemma, r["rejected"])
            rows.append((lemma, r["chosen"], r["rejected"], cat))

    fieldnames = ["lemma", "chosen", "rejected", "n_category"]
    with out.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(fieldnames)
        for r in rows:
            w.writerow(r)

    # Summary
    from collections import Counter
    c = Counter(r[3] for r in rows)
    total = sum(c.values())
    print(f"\n=== n_clue category distribution across {total} pairs ===")
    for cat, n in c.most_common():
        pct = 100 * n / total
        print(f"  {cat:<12} {n:>4}  ({pct:.1f}%)")

    print()
    print("MISCATEGORIZED (PP_BRIDGE — should be y not n):")
    for r in rows:
        if r[3] == "PP_BRIDGE":
            print(f"  {r[0]:<18} chosen={r[1]:<20}  rejected={r[2]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
