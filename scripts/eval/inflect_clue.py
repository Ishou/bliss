#!/usr/bin/env python3
"""Inflect a lemma-form mots-fléchés clue to match a target surface morphology.

Approach:
- Generate clue at the lemma (citation) form: infinitive verb / masc-sing noun
  / masc-sing adjective.
- At lookup time, take the surface form's grammalecte tags, derive the
  inflectional target (mood + person + gender + number, no paradigm prefix),
  find the clue's head token (first content word whose POS matches), and
  inflect it via the morphology index. Other tokens stay verbatim.

This is a deliberately simple first cut — the head-only inflection rule
covers the common crossword patterns ("Aller vite" -> "Vont vite";
"Astre du jour" -> "Astres du jour"; "Couleur du sang" -> stays as-is for
an epicene singular adjective). Multi-token agreement (adjective tracking
its noun's gender, etc.) is left for a v2.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from morphology_index import (
    GENDER_TOKENS,
    MOOD_TOKENS,
    NUMBER_TOKENS,
    PERSON_TOKENS,
    MorphologyIndex,
    classify_surface_pos,
    extract_inflection_target,
)

# Tokens we consider "content words" (eligible to be the head).
_CONTENT_POS = {"verbe", "nom", "adj"}

# Words always kept verbatim (function words / common adverbs / prepositions).
# Keeping this list small — anything not here is allowed to be the head.
_FUNCTION_WORDS = {
    "le", "la", "les", "un", "une", "des", "du", "de", "d",
    "à", "au", "aux", "en", "dans", "sur", "sous", "par", "pour", "avec", "sans",
    "et", "ou", "mais", "donc", "car", "ni", "or",
    "qui", "que", "qu", "dont", "où", "quoi",
    "ce", "cet", "cette", "ces", "ceux", "celle", "celui",
    "son", "sa", "ses", "leur", "leurs", "mon", "ma", "mes", "ton", "ta", "tes",
    "ne", "pas", "plus", "très", "trop", "peu", "bien", "mal",
}

_TOKEN_RE = re.compile(r"[\wÀ-ÿŒœŸ]+|[^\s\wÀ-ÿ]+", re.UNICODE)


@dataclass
class InflectionResult:
    text: str
    flag: str  # '' | 'no-target-pos' | 'no-head' | 'no-inflection' | 'identity'


def _is_alpha_token(tok: str) -> bool:
    return bool(re.match(r"^[\wÀ-ÿŒœŸ]+$", tok))


def _capitalize_first(s: str) -> str:
    return s[:1].upper() + s[1:] if s else s


def inflect_clue(
    clue: str,
    surface_tags: set[str],
    index: MorphologyIndex,
) -> InflectionResult:
    target_pos = classify_surface_pos(surface_tags)
    if target_pos not in _CONTENT_POS:
        # Nothing inflectable to target (e.g. preposition, determiner). Return clue verbatim.
        return InflectionResult(_capitalize_first(clue), "no-target-pos")

    target = extract_inflection_target(surface_tags)
    if not target:
        return InflectionResult(_capitalize_first(clue), "no-target-pos")

    tokens = _TOKEN_RE.findall(clue)
    if not tokens:
        return InflectionResult(clue, "empty")

    head_idx = -1
    head_lemma = ""
    for i, tok in enumerate(tokens):
        if not _is_alpha_token(tok):
            continue
        if tok.lower() in _FUNCTION_WORDS:
            continue
        lemma = index.lemma_of_form(tok.lower(), prefer_pos=target_pos)
        if lemma and index.pos_of_form(tok.lower()) == target_pos:
            head_idx = i
            head_lemma = lemma
            break

    if head_idx < 0:
        # No head with matching POS — clue is structurally incompatible with
        # the surface morphology (e.g. surface is a verb but the clue head is
        # a noun, like "Astre du jour" cluing a verb form). Leave verbatim.
        return InflectionResult(_capitalize_first(clue), "head-pos-mismatch")

    inflected = index.inflect(head_lemma, target)
    if not inflected:
        return InflectionResult(_capitalize_first(clue), "no-inflection")

    if inflected.lower() == tokens[head_idx].lower():
        # Already matches — clue was already in the right form.
        return InflectionResult(_capitalize_first(clue), "identity")

    new_tokens = list(tokens)
    new_tokens[head_idx] = inflected
    rebuilt = _detokenize(new_tokens)
    return InflectionResult(_capitalize_first(rebuilt), "")


def _detokenize(tokens: list[str]) -> str:
    """Glue tokens back together. Punctuation tokens attach to the previous
    word; alphabetic tokens are space-separated."""
    out = []
    for tok in tokens:
        if not _is_alpha_token(tok) and out:
            out[-1] = out[-1] + tok
        else:
            if out:
                out.append(" ")
            out.append(tok)
    return "".join(out)
