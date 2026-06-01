from __future__ import annotations

import argparse
import csv
import random
import re
import sys
import unicodedata
from pathlib import Path

# DBnary POS -> survey enum; properNoun kept distinct (TITAN = nom_commun + nom_propre).
DBNARY_TO_ENUM = {
    "noun": "nom_commun",
    "properNoun": "nom_propre",
    "adjective": "adjectif",
    "verb": "verbe_infinitif",
    "adverb": "adverbe",
}

# ADR-0058 filter-only gloss use: drop a (lemma, pos) whose every sense is obsolete-tagged; gloss text is never emitted.
_OBSOLETE = re.compile(r"\((?:Désuet|Vieilli|Vieux|Archaïsme|Archaïque|Par archaïsme)\)", re.IGNORECASE)


def _strip(s: str) -> str:
    return "".join(
        c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn"
    ).lower()


# {stripped lemma: ({enum_pos: non-obsolete sense count}, {enum_pos: canonical_lemma})}.
# Canonical is tracked per (stripped, enum_pos) so `solde` (noun) and `soldé` (adjective)
# emit distinct surfaces; alphabetical tie-break when multiple variants share (stripped, pos).
def load_dbnary(path: Path) -> dict[str, tuple[dict[str, int], dict[str, str]]]:
    db: dict[str, tuple[dict[str, int], dict[str, str]]] = {}
    with path.open() as f:
        for row in csv.DictReader(f):
            pos = row["pos"].strip()
            if pos not in DBNARY_TO_ENUM:
                continue
            senses = [s for s in (row.get("definition") or "").split("|") if s.strip()]
            live = [s for s in senses if not _OBSOLETE.search(s)]
            if senses and not live:
                continue
            stripped = _strip(row["lemma"])
            counts, canonicals = db.get(stripped, ({}, {}))
            enum_pos = DBNARY_TO_ENUM[pos]
            counts[enum_pos] = counts.get(enum_pos, 0) + (len(live) or 1)
            existing = canonicals.get(enum_pos)
            if existing is None or row["lemma"] < existing:
                canonicals[enum_pos] = row["lemma"]
            db[stripped] = (counts, canonicals)
    return db


def main() -> int:
    p = argparse.ArgumentParser(
        description="Expand a bare word list into a POS-annotated lemma list via DBnary."
    )
    p.add_argument("--words", type=Path, required=True, help="CSV with a `mot` column")
    p.add_argument("--dbnary", type=Path, default=Path("data/dbnary/dbnary_fr.csv"))
    p.add_argument("--out", type=Path, required=True)
    p.add_argument(
        "--multi-pos-fraction",
        type=float,
        default=1.0,
        help="Fraction of multi-POS words that keep ALL their POS. 1.0 = full "
        "multi-POS expansion (training). For a rating campaign use a low value "
        "(e.g. 0.15): most words get their dominant POS only (word breadth), a "
        "small share keep several POS for variety.",
    )
    p.add_argument("--seed", type=int, default=42)
    args = p.parse_args()
    rng = random.Random(args.seed)

    db = load_dbnary(args.dbnary)
    words = [
        line.strip()
        for line in args.words.read_text().splitlines()[1:]
        if line.strip()
    ]

    rows: list[tuple[str, str]] = []
    uncovered: list[str] = []
    for mot in words:
        entry = db.get(_strip(mot))
        if entry is None:
            uncovered.append(mot)
            continue
        counts, canonicals = entry
        # rank by sense-count desc (dominant POS first), tie-break by name
        ranked = sorted(counts, key=lambda p: (-counts[p], p))
        # most multi-POS words collapse to their dominant POS (breadth); a fraction keep all (variety).
        if len(ranked) > 1 and rng.random() >= args.multi_pos_fraction:
            ranked = ranked[:1]
        for pos in sorted(ranked):
            # The DBnary canonical lemma per POS carries the accents the input mot may have lost.
            rows.append((canonicals[pos].upper(), pos))

    with args.out.open("w", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["mot", "pos"])
        w.writerows(rows)

    print(f"words: {len(words)}  rows: {len(rows)}  uncovered: {len(uncovered)}", file=sys.stderr)
    if uncovered:
        print(f"uncovered (no DBnary POS): {uncovered}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
