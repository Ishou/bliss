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


_CONTENT_POS = {"verbe", "nom", "adj"}

_FUNCTION_WORDS = {
    "le", "la", "les", "un", "une", "des", "du", "de", "d",
    "à", "au", "aux", "en", "dans", "sur", "sous", "par", "pour", "avec", "sans",
    "et", "ou", "mais", "donc", "car", "ni", "or",
    "qui", "que", "qu", "dont", "où", "quoi",
    "ce", "cet", "cette", "ces", "ceux", "celle", "celui",
    "son", "sa", "ses", "leur", "leurs", "mon", "ma", "mes", "ton", "ta", "tes",
    "ne", "pas", "plus", "très", "trop", "peu", "bien", "mal",
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
    for lemma, tags in analyses:
        head_pos_set = _classify_pos_set(tags)
        is_lemma_form = lemma.lower() == head_lower
        if is_lemma_form:
            saw_lemma_form = True
            if target_pos_norm in head_pos_set:
                return ValidationResult("ok", "", head)
        elif target_pos_norm in head_pos_set:
            saw_target_pos_inflected = True

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
