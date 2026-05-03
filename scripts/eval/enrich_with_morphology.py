#!/usr/bin/env python3
"""Read data/eval/sample_100_with_definitions.csv + grammalecte's
lexique-grammalecte-fr-v7.7.txt, look each sample word's morphology up by its
Flexion column, and emit data/eval/sample_100_enriched.csv with two extra
columns: `tags` (raw grammalecte Étiquettes) and `morphology` (a short
human-readable French descriptor for the prompt).

Source license: grammalecte lexique is MPL-2.0. The eval data we generate from
it stays under MPL-2.0 (or compatible) per the share-alike.

Usage:
    python scripts/eval/enrich_with_morphology.py --lexique ~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt
"""

import argparse
import csv
import sys
from pathlib import Path

# Tag glossary derived from grammalecte's Étiquettes column.
# Possessive / demonstrative / indefinite determiners are surfaced under their
# school-grammar names ("adjectif possessif", "adjectif démonstratif", ...) which
# is the terminology mots-fléchés clues use.
POS_FR = {
    "nom": "nom",
    "adj": "adjectif",
    "adv": "adverbe",
    "v0": "verbe",
    "v1": "verbe",
    "v2": "verbe",
    "v3": "verbe",
    "prep": "préposition",
    "detpos": "adjectif possessif",
    "detdem": "adjectif démonstratif",
    "detind": "adjectif indéfini",
    "detneg": "adjectif négatif",
    "detnum": "adjectif numéral",
    "detexc": "adjectif exclamatif",
    "detint": "adjectif interrogatif",
    "det": "déterminant",
    "pro": "pronom",
    "propos": "pronom possessif",
    "prodem": "pronom démonstratif",
    "proper": "pronom personnel",
    "proind": "pronom indéfini",
    "proint": "pronom interrogatif",
    "prorel": "pronom relatif",
    "cjco": "conjonction de coordination",
    "cjsub": "conjonction de subordination",
    "interj": "interjection",
    "patr": "patronyme",
    "prn": "prénom",
}
GENDER_FR = {"mas": "masculin", "fem": "féminin", "epi": "épicène"}
NUMBER_FR = {"sg": "singulier", "pl": "pluriel", "inv": "invariable"}
MOOD_FR = {
    "infi": "infinitif",
    "ipre": "indicatif présent",
    "iimp": "indicatif imparfait",
    "ipsi": "indicatif passé simple",
    "ifut": "indicatif futur",
    "cond": "conditionnel",
    "spre": "subjonctif présent",
    "simp": "subjonctif imparfait",
    "impe": "impératif",
    "ppre": "participe présent",
    "ppas": "participe passé",
}
PERSON_FR = {
    "1sg": "1re pers. sing.",
    "2sg": "2e pers. sing.",
    "3sg": "3e pers. sing.",
    "1pl": "1re pers. plur.",
    "2pl": "2e pers. plur.",
    "3pl": "3e pers. plur.",
}

# Priority for picking among multiple POS analyses for the same surface form.
# Closed-class determiners/pronouns sit ABOVE `nom` because for short,
# high-frequency forms (`nos`, `mes`, `ce`, `son`...) grammalecte often also
# carries a rare/technical noun analysis that we don't want to pick.
POS_PRIORITY = [
    "detpos", "detdem", "detind", "detneg", "detnum", "detexc", "detint",
    "propos", "prodem", "proper", "proind", "proint", "prorel",
    "nom", "v1", "v2", "v3", "v0", "adj", "adv",
    "det", "pro", "prep", "cjco", "cjsub", "interj",
]


def parse_lexique(path: Path, words: set[str]) -> dict[str, list[str]]:
    """Return surface form → list of raw `Étiquettes` strings (one per analysis)."""
    out: dict[str, list[str]] = {w: [] for w in words}
    seen_header = False
    f_idx = l_idx = e_idx = -1
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("#"):
                continue
            cols = line.rstrip("\n").split("\t")
            if not seen_header:
                if cols[:1] == ["id"] and "Flexion" in cols and "Étiquettes" in cols:
                    f_idx = cols.index("Flexion")
                    l_idx = cols.index("Lemme")
                    e_idx = cols.index("Étiquettes")
                    seen_header = True
                continue
            if len(cols) <= e_idx:
                continue
            flexion = cols[f_idx]
            if flexion in out:
                out[flexion].append(cols[e_idx])
    return out


def _pos_token(tags: str) -> str:
    """Extract the canonical POS marker (nom, adj, v0..v3, ...). Verb tokens in
    grammalecte carry a suffix (e.g. v1__t___zz, v3_it_q__a) — match by prefix."""
    for tok in tags.split():
        if tok in POS_FR:
            return tok
        if len(tok) >= 2 and tok[0] == "v" and tok[1].isdigit():
            return tok[:2]
    return ""


def pick_primary(analyses: list[str]) -> str:
    """Pick the primary analysis when multiple exist (e.g. bête = adj or nom).
    Prefer noun > verb > adjective > others."""
    if not analyses:
        return ""
    if len(analyses) == 1:
        return analyses[0]
    def rank(tags: str) -> int:
        pos = _pos_token(tags)
        try:
            return POS_PRIORITY.index(pos)
        except ValueError:
            return len(POS_PRIORITY)
    return min(analyses, key=rank)


def render_morphology(tags: str) -> str:
    """Translate grammalecte tags to a short French descriptor for the prompt."""
    if not tags:
        return ""
    parts: list[str] = []
    seen = {"pos": False, "gender": False, "number": False, "mood": False, "person": False}
    pos_label = ""
    pos_key = _pos_token(tags)
    if pos_key:
        pos_label = POS_FR.get(pos_key, "")
        seen["pos"] = True
    for tok in tags.split():
        clean = tok.rstrip("!?")
        if not seen["gender"] and clean in GENDER_FR:
            parts.append(GENDER_FR[clean])
            seen["gender"] = True
        elif not seen["number"] and clean in NUMBER_FR:
            parts.append(NUMBER_FR[clean])
            seen["number"] = True
        elif not seen["mood"] and clean in MOOD_FR:
            parts.append(MOOD_FR[clean])
            seen["mood"] = True
        elif not seen["person"] and clean in PERSON_FR:
            parts.append(PERSON_FR[clean])
            seen["person"] = True
    if pos_label:
        parts.insert(0, pos_label)
    return ", ".join(parts)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lexique", type=Path, required=True, help="path to lexique-grammalecte-fr-vX.txt")
    parser.add_argument("--input", type=Path, default=None)
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent.parent
    src = args.input or repo_root / "data" / "eval" / "sample_100_with_definitions.csv"
    out = args.output or repo_root / "data" / "eval" / "sample_100_enriched.csv"

    if not args.lexique.exists():
        print(f"missing lexique: {args.lexique}", file=sys.stderr)
        sys.exit(1)
    if not src.exists():
        print(f"missing {src}", file=sys.stderr)
        sys.exit(1)

    with src.open(encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))

    sample_words = {r["word"] for r in rows}
    print(f"parsing {args.lexique} for {len(sample_words)} words...", file=sys.stderr)
    by_word = parse_lexique(args.lexique, sample_words)

    misses = 0
    for r in rows:
        analyses = by_word.get(r["word"], [])
        primary = pick_primary(analyses)
        r["tags"] = primary
        r["morphology"] = render_morphology(primary)
        if not primary:
            misses += 1

    fieldnames = list(rows[0].keys())
    if "tags" not in fieldnames:
        fieldnames.append("tags")
    if "morphology" not in fieldnames:
        fieldnames.append("morphology")

    with out.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        for r in rows:
            writer.writerow({k: r.get(k, "") for k in fieldnames})

    print(f"Wrote {len(rows)} rows to {out.relative_to(repo_root)} ({misses} morph misses)")
    # Quick spot-check
    for r in rows[:5]:
        print(f"  {r['word']:20s} -> {r['morphology']}")


if __name__ == "__main__":
    main()
