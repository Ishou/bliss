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
import re
import sys
from pathlib import Path

from morphology_index import MorphologyIndex
from fetch_dbnary_for_sample import query_sparql

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
#
# Verb-over-noun was tested briefly (iter4 attempt) but rejected: surface
# forms like `chapelle` are dual-tagged (chapelle the chapel + 1sg of the
# unrelated verb chapeler "to crumb stale bread"). Pure verb-priority
# rewrites the lemma to a semantically-unrelated word. A frequency-aware
# verb-priority (rewrite only when verb occurrences are within an order of
# magnitude of noun occurrences) would distinguish amende/amender (close)
# from chapelle/chapeler (far). Future work.
POS_PRIORITY = [
    "detpos", "detdem", "detind", "detneg", "detnum", "detexc", "detint",
    "propos", "prodem", "proper", "proind", "proint", "prorel",
    "nom", "v1", "v2", "v3", "v0", "adj", "adv",
    "det", "pro", "prep", "cjco", "cjsub", "interj",
]


def parse_lexique(path: Path, words: set[str]) -> dict[str, list[tuple[str, str]]]:
    """Return surface form → list of (lemma, raw `Étiquettes`) tuples — one
    tuple per grammalecte analysis. Lemma is what grammalecte assigns to that
    specific analysis (`amende → amender` for the verb form, `amende → amende`
    for the noun); used by the enricher to rewrite the prompt's lemma when
    the chosen analysis disagrees with the input row's lemma column."""
    out: dict[str, list[tuple[str, str]]] = {w: [] for w in words}
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
                out[flexion].append((cols[l_idx].lower(), cols[e_idx]))
    return out


def load_lemma_pos_freq(path: Path) -> dict[tuple[str, str], int]:
    """Sum 'Total occurrences' across all surface rows for each (lemma, pos)
    pair. POS is the canonical class returned by `_pos_token` (nom/v0..v3/adj/...).
    Used to decide whether a dual-tagged surface (e.g. `amende` is both nom
    `amende` and 1sg of verb `amender`) should rewrite to the verb infinitive:
    `amender` lemma has ~1M total occurrences vs `amende` nom ~5.4M (close
    enough to rewrite); `chapeler` has ~400 total vs `chapelle` ~5.5M (lexical
    accident — leave the noun alone)."""
    out: dict[tuple[str, str], int] = {}
    seen_header = False
    l_idx = e_idx = t_idx = -1
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("#"):
                continue
            cols = line.rstrip("\n").split("\t")
            if not seen_header:
                if cols[:1] == ["id"] and "Lemme" in cols and "Total occurrences" in cols:
                    l_idx = cols.index("Lemme")
                    e_idx = cols.index("Étiquettes")
                    t_idx = cols.index("Total occurrences")
                    seen_header = True
                continue
            if len(cols) <= max(l_idx, e_idx, t_idx):
                continue
            pos = _pos_token(cols[e_idx])
            if not pos:
                continue
            try:
                freq = int(cols[t_idx])
            except ValueError:
                continue
            key = (cols[l_idx].lower(), pos)
            out[key] = out.get(key, 0) + freq
    return out


# Frequency-aware verb-over-noun rewrite thresholds.
# `amende` (nom 5.4M) / `amender` (verb 1M)  → ratio 0.18 → REWRITE
# `chapelle` (nom 5.5M) / `chapeler` (verb ~400) → ratio 8e-5 → KEEP
# `colloque` (nom 18k) / `colloquer` (verb ~30) → ratio 0.0017 → KEEP
# 5% ratio + 1000 minimum cleanly separates real verb forms from accidents.
_VERB_REWRITE_RATIO = 0.05
_VERB_REWRITE_MIN_FREQ = 1000


def maybe_rewrite_to_verb(
    surface: str,
    current_lemma: str,
    analyses: list[tuple[str, str]],
    lemma_freq: dict[tuple[str, str], int],
) -> str | None:
    """Return the verb infinitive when the surface is dual-tagged (noun +
    frequent verb). Used as a TWIN-DETECTOR — caller emits both noun and
    verb as separate clue candidates, since pure frequency can't tell us
    whether the senses are related (`maîtrise/maîtriser` share meaning;
    `amende/amender` don't). The chapelle/chapeler family is filtered out
    here (verb freq << threshold) because there's no plausible verb sense."""
    noun_lemma: str | None = None
    verb_lemma: str | None = None
    for lemma, tags in analyses:
        pos = _pos_token(tags)
        if pos == "nom" and lemma == current_lemma.lower():
            noun_lemma = lemma
        elif pos and pos[0] == "v" and len(pos) > 1 and pos[1].isdigit():
            verb_lemma = lemma  # last verb wins; in practice 1 verb per surface
    if not noun_lemma or not verb_lemma or verb_lemma == noun_lemma:
        return None
    noun_freq = lemma_freq.get((noun_lemma, "nom"), 0)
    verb_pos = next(
        (_pos_token(t) for l, t in analyses
         if l == verb_lemma and _pos_token(t).startswith("v")),
        "",
    )
    verb_freq = lemma_freq.get((verb_lemma, verb_pos), 0)
    if verb_freq < _VERB_REWRITE_MIN_FREQ:
        return None
    if noun_freq > 0 and verb_freq / noun_freq < _VERB_REWRITE_RATIO:
        return None
    return verb_lemma


def _pos_token(tags: str) -> str:
    """Extract the canonical POS marker (nom, adj, v0..v3, ...). Verb tokens in
    grammalecte carry a suffix (e.g. v1__t___zz, v3_it_q__a) — match by prefix."""
    for tok in tags.split():
        if tok in POS_FR:
            return tok
        if len(tok) >= 2 and tok[0] == "v" and tok[1].isdigit():
            return tok[:2]
    return ""


def pick_primary(analyses: list[tuple[str, str]]) -> tuple[str, str]:
    """Pick the primary (lemma, tags) when multiple analyses exist
    (e.g. `amende = (amender, v1...) | (amende, nom...)`). POS_PRIORITY
    is consulted in order; first match wins. Returns ('', '') when there
    are no analyses."""
    if not analyses:
        return ("", "")
    if len(analyses) == 1:
        return analyses[0]

    def rank(item: tuple[str, str]) -> int:
        pos = _pos_token(item[1])
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


def _all_forms_of(lemma: str, lemma_to_forms: dict[str, set[str]]) -> set[str]:
    return lemma_to_forms.get(lemma.lower(), {lemma.lower()})


_SENTENCE_SPLIT = re.compile(r"(?<=[\.;])\s+")
_LEADING_TAG_RE = re.compile(r"^\s*\(([^)]+)\)")
# Register markers that indicate an archaic / disused sense. The clue-
# generation pipeline saw the model parrot these as "modern" senses
# (`bourgeois → "Habitant urbain"` from "(Vieilli) Citoyen d'une ville",
# `université → "Corps étudiant"` from "(Vieilli) Commune, communauté"),
# so we deprioritise them in favour of any non-archaic alternative.
_ARCHAIC_MARKERS = {"vieilli", "désuet", "vieux", "anciennement", "archaïsme", "archaïque"}


def _has_archaic_tag(sentence: str) -> bool:
    """True when any LEADING parenthetical tag of the sentence is archaic.

    DBnary stacks register tags at the start: '(Vieilli) (Politique) Partisan...'
    so we walk all leading `(...)` groups, not just the first one.
    """
    s = sentence.lstrip()
    while True:
        m = _LEADING_TAG_RE.match(s)
        if not m:
            return False
        tag = m.group(1).lower()
        if any(marker in tag for marker in _ARCHAIC_MARKERS):
            return True
        s = s[m.end():].lstrip()


def clean_definition(definition: str, lemma: str, lemma_to_forms: dict[str, set[str]]) -> str:
    """Refine a DBnary definition so it doesn't self-reference the lemma AND
    doesn't surface an archaic-tagged sense when a modern alternative exists.

    Three-level walk:
    1. Senses (pipe-delimited).
    2. Within each sense, drop sentences containing the lemma or any
       inflection (self-reference filter).
    3. Tier the surviving senses by archaic-tag presence; return the first
       sense from the non-archaic tier when one exists, else fall back to
       all surviving senses, non-archaic first then archaic; empty when
       none survive. Returned pipe-delimited so downstream can render them
       as a numbered list in the prompt — letting the model pick the most
       cluable sense rather than locking to sense_1.
    """
    if not definition:
        return ""
    forms = _all_forms_of(lemma, lemma_to_forms)
    pattern = re.compile(
        r"\b(?:" + "|".join(re.escape(f) for f in sorted(forms, key=len, reverse=True)) + r")\b",
        re.IGNORECASE,
    )
    non_archaic: list[str] = []
    archaic: list[str] = []
    for sense in (s.strip() for s in definition.split("|")):
        if not sense:
            continue
        sentences = [s.strip() for s in _SENTENCE_SPLIT.split(sense) if s.strip()]
        clean = [s for s in sentences if not pattern.search(s)]
        if not clean:
            continue
        text = " ".join(clean)
        # Sense is "archaic" when ALL surviving sentences have an archaic
        # leading tag — a single non-archaic sentence makes the whole sense
        # serviceable.
        if all(_has_archaic_tag(s) for s in clean):
            archaic.append(text)
        else:
            non_archaic.append(text)
    return "|".join(non_archaic + archaic)


def clean_synonyms(synonyms_csv: str, lemma: str, lemma_to_forms: dict[str, set[str]]) -> str:
    """Drop any synonym that is the lemma itself or one of its inflections —
    DBnary occasionally surfaces such 'synonyms' (the lemma's own forms),
    which are noise, not synonyms."""
    if not synonyms_csv:
        return ""
    forms = _all_forms_of(lemma, lemma_to_forms)
    kept = [
        s for s in (p.strip() for p in synonyms_csv.split("|"))
        if s and s.lower() not in forms
    ]
    return "|".join(kept)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lexique", type=Path, required=True, help="path to lexique-grammalecte-fr-vX.txt")
    parser.add_argument("--input", type=Path, default=None)
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--no-dbnary", action="store_true",
                        help="skip DBnary SPARQL refetch for twin rows (use when endpoint is down)")
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

    print(f"computing lemma frequencies from {args.lexique}...", file=sys.stderr)
    lemma_freq = load_lemma_pos_freq(args.lexique)

    # Full grammalecte index — needed for the lemma -> all-inflections lookup
    # used to detect (and drop) self-referential definitions and lemma-form
    # synonyms.
    print(f"loading full grammalecte index from {args.lexique}...", file=sys.stderr)
    index = MorphologyIndex.load(args.lexique)
    lemma_to_forms: dict[str, set[str]] = {}
    for lemma, entries in index.by_lemma.items():
        forms = {lemma}
        for surface, _tags in entries:
            forms.add(surface)
        lemma_to_forms[lemma] = forms

    def enrich_row(r: dict[str, str], analyses: list[tuple[str, str]]) -> None:
        """Fill in tags, morphology, cleaned definition + synonyms in place.
        Mutates the dict; does not return."""
        nonlocal misses, selfref_defs, dropped_synonyms
        _, primary = pick_primary(analyses)
        r["tags"] = primary
        r["morphology"] = render_morphology(primary)
        if not primary:
            misses += 1
        lemma = (r.get("lemma") or r["word"]).strip().lower()
        original_def = r.get("definition", "")
        original_syns = r.get("synonyms", "")
        cleaned_def = clean_definition(original_def, lemma, lemma_to_forms)
        cleaned_syns = clean_synonyms(original_syns, lemma, lemma_to_forms)
        if original_def and not cleaned_def:
            selfref_defs += 1
        if original_syns and len(original_syns.split("|")) != len(cleaned_syns.split("|") if cleaned_syns else []):
            dropped_synonyms += 1
        r["definition"] = cleaned_def
        r["synonyms"] = cleaned_syns

    misses = 0
    selfref_defs = 0
    dropped_synonyms = 0
    twins: list[dict[str, str]] = []
    existing_lemmas = {(r.get("lemma") or r["word"]).strip().lower() for r in rows}
    for r in rows:
        analyses = by_word.get(r["word"], [])
        # Detect dual-tagged surfaces (noun + frequent verb) — emit a TWIN row
        # for the verb infinitive instead of rewriting the original. Both
        # clues get generated; downstream picks the better one. amende stays
        # amende (the noun); amender appears as a separate twin row.
        current_lemma = (r.get("lemma") or r["word"]).strip().lower()
        twin_lemma = maybe_rewrite_to_verb(r["word"], current_lemma, analyses, lemma_freq)
        r["twin_of"] = ""
        enrich_row(r, analyses)

        if twin_lemma and twin_lemma not in existing_lemmas:
            existing_lemmas.add(twin_lemma)
            twin_analyses = [
                (lemma, " ".join(sorted(tags)))
                for lemma, tags in index.by_form.get(twin_lemma, [])
            ]
            if args.no_dbnary:
                t_pos, t_def, t_syn = "", "", ""
            else:
                try:
                    t_pos, t_def, t_syn = query_sparql(twin_lemma, twin_lemma)
                except Exception as e:
                    print(f"  twin DBnary fetch failed for {twin_lemma!r}: {e}", file=sys.stderr)
                    t_pos, t_def, t_syn = "", "", ""
            twin = {
                **r,
                "word": twin_lemma,
                "lemma": twin_lemma,
                "pos": t_pos,
                "definition": t_def,
                "synonyms": t_syn,
                "twin_of": current_lemma,
            }
            enrich_row(twin, twin_analyses)
            twins.append(twin)

    rows.extend(twins)

    fieldnames = list(rows[0].keys())
    for col in ("tags", "morphology", "twin_of"):
        if col not in fieldnames:
            fieldnames.append(col)

    with out.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        for r in rows:
            writer.writerow({k: r.get(k, "") for k in fieldnames})

    try:
        out_disp = out.resolve().relative_to(repo_root)
    except ValueError:
        out_disp = out
    print(f"Wrote {len(rows)} rows to {out_disp} "
          f"({misses} morph misses, {selfref_defs} self-ref defs blanked, "
          f"{dropped_synonyms} rows had lemma-form synonyms dropped, "
          f"{len(twins)} verb twins added)")
    for t in twins:
        print(f"  twin: {t['twin_of']} + {t['word']}")
    # Quick spot-check
    for r in rows[:5]:
        print(f"  {r['word']:20s} morph={r['morphology']!r:45s} def={r['definition'][:50]!r}")


if __name__ == "__main__":
    main()
