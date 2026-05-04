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
  no-head          no content word found in clue (only function words / empty)
  unknown-head     head not in grammalecte (likely a hallucination or proper noun)
  head-not-lemma   head exists but is an inflected form, not citation form
  pos-mismatch     head is a lemma form but the POS class doesn't match the target
"""

from __future__ import annotations

import re
from dataclasses import dataclass

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


def validate_lemma_clue(
    clue: str,
    target_lemma: str,
    target_pos: str,
    index: MorphologyIndex,
) -> ValidationResult:
    if not clue.strip():
        return ValidationResult("no-head", "empty clue")

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
