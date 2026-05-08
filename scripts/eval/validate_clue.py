#!/usr/bin/env python3
"""Validate that a generated clue is at lemma (citation) form and POS-matched.

A clue is acceptable iff the first content-word token of the clue:
- is recognised by grammalecte (in the lexique), AND
- has at least one analysis where surface == lemma (citation form), AND
- that analysis is the same POS class (verbe / nom / adj) as the target lemma.

This catches the two main model-output failure modes from the previous run:
- Verb lemma → noun clue ("inventerons" cued as "Créateur d'idées nouvelles")
- Lemma form requested → inflected form returned ("manger" cued as "mangerai")

Returns (flag, reason) where flag is one of:
  ok               clue passes
  blocklisted      clue text matches an entry in the runtime blocklist
                   (sourced from `inapproprié` ratings — see triage_runtime_feedback.py)
  no-head          no content word found in clue (only function words / empty)
  unknown-head     head not in grammalecte (likely a hallucination or proper noun)
  head-not-lemma   head exists but is an inflected form, not citation form
  pos-mismatch     head is a lemma form but the POS class doesn't match the target
  pleonasm         head verb's lemma already encodes the trailing modifier
                   ("Associer ensemble", "Monter en haut", "Prévoir à l'avance")
"""

from __future__ import annotations

import csv
import re
from dataclasses import dataclass
from pathlib import Path

from morphology_index import MorphologyIndex


_CONTENT_POS = {"verbe", "nom", "adj", "adv"}

_FUNCTION_WORDS = {
    # articles + determiners
    "le", "la", "les", "un", "une", "des", "du", "de", "d",
    # prepositions
    "à", "au", "aux", "en", "dans", "sur", "sous", "par", "pour", "avec", "sans",
    # conjunctions
    "et", "ou", "mais", "donc", "car", "ni", "or",
    # relative / interrogative
    "qui", "que", "qu", "dont", "où", "quoi",
    # demonstratives
    "ce", "cet", "cette", "ces", "ceux", "celle", "celui",
    # possessives
    "son", "sa", "ses", "leur", "leurs", "mon", "ma", "mes", "ton", "ta", "tes",
    # negation + degree adverbs
    "ne", "pas", "plus", "très", "trop", "peu", "bien", "mal",
    # subject pronouns (rare in lemma clues but harmless to skip)
    "je", "tu", "il", "elle", "ils", "elles", "on",
    # reflexive + object pronouns — pronominal verbs surface here as
    # "Se soulever ...", "Se cabrer ..."; without these, the head loop
    # picks the pronoun and the validator wrongly reports pos-mismatch.
    "se", "s", "me", "m", "te", "t", "nous", "vous", "lui", "y",
    # sundry
    "soi",
}

_TOKEN_RE = re.compile(r"[\wÀ-ÿŒœŸ]+", re.UNICODE)


@dataclass
class ValidationResult:
    flag: str
    reason: str
    head: str = ""


def _classify_pos_set(tags: frozenset[str]) -> set[str]:
    """Return every content POS class present in the tag set. Many French
    words are tagged with both nom AND adj in a single analysis (sauvage,
    rouge, ...); the single-class view loses that overlap."""
    classes: set[str] = set()
    for t in tags:
        if t.startswith("v") and len(t) > 1 and t[1].isdigit():
            classes.add("verbe")
    if "nom" in tags:
        classes.add("nom")
    if "adj" in tags:
        classes.add("adj")
    if "adv" in tags:
        classes.add("adv")
    return classes


def _classify_pos(tags: frozenset[str]) -> str:
    """Backward-compat single-POS view; first match wins."""
    classes = _classify_pos_set(tags)
    for pos in ("verbe", "nom", "adj"):
        if pos in classes:
            return pos
    return ""


def _find_head(clue: str) -> str:
    for tok in _TOKEN_RE.findall(clue):
        if tok.lower() in _FUNCTION_WORDS:
            continue
        return tok
    return ""


def _find_lemma_family_leak(
    clue: str,
    target_lemma: str,
    index: MorphologyIndex,
) -> str | None:
    """Return the offending token if any clue token (not just the head) belongs
    to the target lemma's grammalecte inflection family. None when clean.

    Catches non-head self-references like:
      impur → 'Matière impure'   ('impure' is fem-sg of impur)
      asseoir → "Faire s'asseoir" ('asseoir' is the lemma itself)
    """
    target = target_lemma.lower().strip()
    if not target:
        return None
    family = {target}
    for surface, _tags in index.by_lemma.get(target, []):
        family.add(surface)
    if len(family) <= 1:
        return None
    for tok in _TOKEN_RE.findall(clue):
        tl = tok.lower()
        if tl in family:
            return tok
    return None


# Minimum overlap length for the stem-leak check. Below 5, common French
# affixes (de-, re-, -er, -ier) generate too many false positives. At 5+,
# overlaps are strongly indicative of shared root.
_STEM_LEAK_MIN = 5


def _longest_common_prefix(a: str, b: str) -> int:
    n = min(len(a), len(b))
    for i in range(n):
        if a[i] != b[i]:
            return i
    return n


def _find_stem_leak(clue: str, target_lemma: str) -> str | None:
    """Return the offending token if any clue token shares ≥5 chars of stem
    with the target lemma. Catches:
      destructeur → 'Agent de destruction' (LCP "destruct" = 8 chars)
      ébattre     → 'Battre joyeusement'   (battre is substring of ébattre)
      chapelle    → 'Petit chapelet'       (LCP "chapel" = 6 chars)

    Two checks: longest-common-prefix ≥ 5, OR substring containment when
    both forms are ≥ 5 chars. Short targets (< 5 chars) skip the check
    entirely — French has too many short words to draw stem boundaries
    reliably below that length."""
    target = target_lemma.lower().strip()
    if len(target) < _STEM_LEAK_MIN:
        return None
    for tok in _TOKEN_RE.findall(clue):
        tl = tok.lower()
        if tl in _FUNCTION_WORDS or len(tl) < _STEM_LEAK_MIN:
            continue
        if tl == target:
            continue  # exact match is handled by the family-leak check
        if _longest_common_prefix(target, tl) >= _STEM_LEAK_MIN:
            return tok
        if tl in target or target in tl:
            return tok
    return None


# Pleonasm patterns: head verb's lemma whose semantics already encode the
# trailing modifier. "Associer ensemble" is redundant because associer ≡
# "to bring together"; "Monter en haut" is redundant because monter ≡ "to
# go up". Patterns are conservative — false positives kill legitimate clues
# like "Mettre ensemble" (which is the canonical lemma definition of réunir),
# so we only list head verbs that *intrinsically* mean the trailing token.
#
# Each entry: (set of head-verb lemmas, regex matching the redundant tail).
# A pleonasm fires when the clue's head token is one of the lemmas AND the
# rest of the clue contains the redundant tail. Verb conjugation is handled
# by stem-prefix matching: any token that starts with the lemma stem (minus
# the -er/-ir suffix) and shares ≥4 prefix chars counts as the head's
# inflected form.
_PLEONASM_RULES: tuple[tuple[frozenset[str], "re.Pattern[str]"], ...] = (
    # X + ensemble: X already means "join / unite / bring together".
    (frozenset({
        "associer", "unir", "joindre", "rejoindre", "réunir",
        "assembler", "rassembler", "regrouper",
        "mêler",
        "lier", "relier", "souder", "fusionner", "conjuguer",
        "marier", "allier", "apparier", "coupler", "accoupler",
        "fédérer", "confédérer",
        "additionner", "ajouter",
        "enchaîner",
    }), re.compile(r"\bensemble\b", re.IGNORECASE)),
    # Directional: monter/grimper/gravir + en haut / vers le haut.
    (frozenset({"monter", "grimper", "gravir", "ascensionner", "escalader"}),
     re.compile(r"\b(?:en|vers\s+le)\s+haut\b", re.IGNORECASE)),
    # Directional: descendre/tomber/baisser + en bas / vers le bas.
    (frozenset({"descendre", "tomber", "chuter", "baisser", "abaisser"}),
     re.compile(r"\b(?:en|vers\s+le)\s+bas\b", re.IGNORECASE)),
    # Out / in.
    (frozenset({"sortir"}), re.compile(r"\bdehors\b", re.IGNORECASE)),
    (frozenset({"entrer"}), re.compile(r"\bdedans\b", re.IGNORECASE)),
    # Forward / backward.
    (frozenset({"avancer", "progresser"}),
     re.compile(r"\ben\s+avant\b", re.IGNORECASE)),
    (frozenset({"reculer", "rétrograder"}),
     re.compile(r"\ben\s+arrière\b", re.IGNORECASE)),
    # Repetition: re-X verbs + à nouveau.
    (frozenset({
        "répéter", "recommencer", "refaire", "redire",
        "reprendre", "réitérer", "réessayer", "relire", "revoir",
    }), re.compile(r"\bà\s+nouveau\b", re.IGNORECASE)),
    # Anticipation: anticiper/prévoir/planifier + à l'avance.
    (frozenset({"anticiper", "prévoir", "planifier", "préméditer", "programmer"}),
     re.compile(r"\bà\s+l[’']avance\b", re.IGNORECASE)),
    # Reciprocity: verb already implies mutual action + mutuellement.
    (frozenset({"entraider", "s'entraider", "coopérer", "collaborer", "s'associer"}),
     re.compile(r"\bmutuellement\b", re.IGNORECASE)),
    # Completeness: remplir/saturer/vider + complètement.
    (frozenset({"remplir", "saturer", "vider"}),
     re.compile(r"\bcomplètement\b", re.IGNORECASE)),
)


def _head_token_matches_lemma(head: str, lemma: str) -> bool:
    """Return True iff `head` is `lemma` itself or a plausible inflection of it.

    We don't want to thread the morphology index through here (the validator
    already pays for one lookup elsewhere), so we use a stem-prefix rule:
    drop the -er/-ir/-re/-oir suffix from the lemma, then accept any head
    token that starts with the stem and shares ≥4 chars. Avoids the obvious
    false positives ("aller" vs "alleger" share 4 chars but stems differ;
    we anchor on the lemma's stem and require startswith)."""
    h = head.lower()
    l = lemma.lower()
    if h == l:
        return True
    # Reflexive lemmas ("s'entraider") strip the leading "s'".
    if l.startswith("s'") and h == l[2:]:
        return True
    # Strip common French verb suffixes to get the stem. Longest suffix
    # first so "prévoir" → "prév" (not "prévo"); otherwise the passé simple
    # forms "prévit"/"prévis" miss the prefix check.
    stem = l
    for suf in ("oir", "er", "ir", "re"):
        if stem.endswith(suf) and len(stem) > len(suf) + 2:
            stem = stem[: -len(suf)]
            break
    if len(stem) < 3:
        return False
    return h.startswith(stem) and len(h) >= len(stem)


def _find_pleonasm(clue: str) -> str | None:
    """Return the redundant tail token if the clue is pleonastic, else None.

    A clue is pleonastic when its head verb's lemma already encodes the
    trailing modifier — "Associer ensemble", "Monter en haut". The list
    of patterns is the closed set in `_PLEONASM_RULES`; anything outside
    that set is left to the human-curated rating loop.

    Caller (validate_lemma_clue) will surface the leak token so the LoRA
    candidate-picker can log which alternative tail it dropped."""
    head = _find_head(clue)
    if not head:
        return None
    head_lower = head.lower()
    for lemmas, tail_re in _PLEONASM_RULES:
        if not any(_head_token_matches_lemma(head_lower, l) for l in lemmas):
            continue
        m = tail_re.search(clue)
        if m is not None:
            return m.group(0)
    return None


_WS_RE = re.compile(r"\s+")


def _normalize_blocklist_key(s: str) -> str:
    return _WS_RE.sub(" ", s.strip().lower())


def load_blocklist(path: Path) -> frozenset[str]:
    """Load `data/eval/blocklist_clues.csv` into a frozenset of normalized
    clue strings. Missing file → empty set (degrades gracefully when the
    file hasn't been seeded yet). Empty rows skipped."""
    p = Path(path)
    if not p.exists():
        return frozenset()
    out: set[str] = set()
    with p.open(encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        for row in reader:
            clue = (row.get("clue") or "").strip()
            if not clue:
                continue
            out.add(_normalize_blocklist_key(clue))
    return frozenset(out)


def validate_lemma_clue(
    clue: str,
    target_lemma: str,
    target_pos: str,
    index: MorphologyIndex,
    blocklist: frozenset[str] | None = None,
) -> ValidationResult:
    if not clue.strip():
        return ValidationResult("no-head", "empty clue")

    # Blocklist gate runs before any other check — a clue rated `inapproprié`
    # in runtime_feedback must never re-ship, even if it would pass every
    # structural gate. Match is case- and whitespace-insensitive.
    if blocklist:
        if _normalize_blocklist_key(clue) in blocklist:
            return ValidationResult("blocklisted", "clue is on the blocklist")

    # Pixel-fit gate (the no-overflow contract). Loaded lazily so callers
    # without Pillow installed don't pay an import cost; clue_metrics is
    # cheap once loaded.
    from clue_metrics import fits_single_cell  # noqa: PLC0415
    if not fits_single_cell(clue.strip()):
        return ValidationResult(
            "too-long",
            "clue overflows the reference single-clue cell at 0.18 ratio",
        )

    head = _find_head(clue)
    if not head:
        return ValidationResult("no-head", "no content word in clue")

    # Whole-clue self-reference: any token (not just head) that belongs to the
    # target lemma's inflection family is a leak. Catches "Matière impure" for
    # impur and "Faire s'asseoir" for asseoir.
    leak = _find_lemma_family_leak(clue, target_lemma, index)
    if leak is not None:
        return ValidationResult(
            "self-reference",
            f"clue contains '{leak}' from target lemma's inflection family",
            head,
        )

    # Stem leak: any token sharing ≥5 chars of prefix or substring with the
    # target lemma indicates a derivational/etymological relationship that
    # the inflection-family check misses (different lemmas, same root).
    # Catches destructeur → 'destruction', ébattre → 'battre'.
    stem_leak = _find_stem_leak(clue, target_lemma)
    if stem_leak is not None:
        return ValidationResult(
            "stem-leak",
            f"clue token '{stem_leak}' shares root with target lemma",
            head,
        )

    # Pleonasm: head verb's lemma already encodes the trailing modifier.
    # "Associer ensemble" → unir, "Monter en haut" → gravir, etc. The LoRA
    # iter10 corpus produced 5 of these; the inflater faithfully propagated
    # them across ~70 surface forms. Reject so the picker drops to the next
    # candidate at generation time, and so build_surface_clues.py's
    # `validation_flag == 'ok'` filter excludes already-shipped pleonasms
    # from the next pipeline run.
    pleonasm_tail = _find_pleonasm(clue)
    if pleonasm_tail is not None:
        return ValidationResult(
            "pleonasm",
            f"clue head '{head}' makes '{pleonasm_tail}' redundant",
            head,
        )

    head_lower = head.lower()
    target_lower = target_lemma.lower().strip()

    if head_lower == target_lower:
        return ValidationResult("self-reference", f"clue head '{head}' is the target lemma", head)

    analyses = index.lookup_form(head_lower)
    if not analyses:
        return ValidationResult("unknown-head", f"'{head}' not in grammalecte", head)

    target_pos_norm = target_pos.lower().strip()
    if target_pos_norm not in _CONTENT_POS:
        for lemma, tags in analyses:
            if lemma.lower() == head_lower and _classify_pos_set(tags) & _CONTENT_POS:
                return ValidationResult("ok", "", head)
        return ValidationResult("head-not-lemma", f"'{head}' is not a citation form", head)

    saw_lemma_form = False
    saw_target_pos_inflected = False
    adv_phrasal_match = False  # adverb targets accept phrasal heads (En -ant, Avec X, ...)
    for lemma, tags in analyses:
        head_pos_set = _classify_pos_set(tags)
        is_lemma_form = lemma.lower() == head_lower
        if is_lemma_form:
            saw_lemma_form = True
            if target_pos_norm in head_pos_set:
                return ValidationResult("ok", "", head)
            # French adverbs are routinely clued by phrasal heads:
            # 'En souriant' (gérondif), 'Avec sagesse' (nom), 'De manière brusque' (adj).
            # Accept any content-POS citation-form head when target is adv.
            if target_pos_norm == "adv" and head_pos_set & _CONTENT_POS:
                adv_phrasal_match = True
        elif target_pos_norm in head_pos_set:
            saw_target_pos_inflected = True

    if adv_phrasal_match:
        return ValidationResult("ok", "", head)

    if saw_lemma_form:
        return ValidationResult(
            "pos-mismatch",
            f"head '{head}' is a lemma but POS != {target_pos_norm}",
            head,
        )
    if saw_target_pos_inflected:
        return ValidationResult(
            "head-not-lemma",
            f"head '{head}' is a {target_pos_norm} but not in citation form",
            head,
        )
    return ValidationResult(
        "head-not-lemma",
        f"head '{head}' has no {target_pos_norm} lemma analysis",
        head,
    )
