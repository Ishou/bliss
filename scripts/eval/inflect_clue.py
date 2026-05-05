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

# Pre-head adjectives in French — a small closed set that conventionally
# precedes the noun (petit oiseau, vieille femme, bel arbre). When a clue
# starts with one of these, the actual head is later in the token stream;
# we demote them as head candidates.
_PRE_HEAD_ADJ_LEMMAS = {
    "petit", "grand", "gros", "beau", "joli", "vieux", "jeune",
    "bon", "mauvais", "autre", "premier", "dernier", "même",
    "long", "haut", "court", "nouveau", "saint", "vrai", "faux",
}

# In French crosswords, the clue's gender doesn't need to match the surface's
# gender for nominal targets — "Arrêt" (mas) is a fine clue for "Halte" (fem).
# Verbs and adjectives must keep agreement; nouns relax to number-only.
_RELAX_GENDER_FOR = {"nom"}

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

    # Rank candidate heads. Demote tokens whose lemma is a known pre-head
    # adjective (petit, grand, beau, vieux …) so they don't capture the head
    # role from the actual noun. Ties break by leftmost.
    candidates: list[tuple[int, int, str]] = []  # (rank, position, lemma)
    for i, tok in enumerate(tokens):
        if not _is_alpha_token(tok):
            continue
        if tok.lower() in _FUNCTION_WORDS:
            continue
        if target_pos not in index.pos_classes_of_form(tok.lower()):
            continue
        lemma = index.lemma_of_form(tok.lower(), prefer_pos=target_pos)
        if not lemma:
            continue
        # Rank: pre-head adj demoted to back, rest in clue order.
        rank = 1 if lemma.lower() in _PRE_HEAD_ADJ_LEMMAS else 0
        candidates.append((rank, i, lemma))
    candidates.sort()
    head_idx = -1
    head_lemma = ""
    if candidates:
        _, head_idx, head_lemma = candidates[0]

    if head_idx < 0:
        # No head with matching POS — clue is structurally incompatible with
        # the surface morphology (e.g. surface is a verb but the clue head is
        # a noun, like "Astre du jour" cluing a verb form). Leave verbatim.
        return InflectionResult(_capitalize_first(clue), "head-pos-mismatch")

    # For nominal targets, gender relaxation is best-effort: try strict
    # gender first so a paradigm with both masc and fem forms (voleur →
    # voleuse) flips correctly. Only drop the gender constraint if no
    # exact-gender form exists (Astre is intrinsically masculine — it
    # stays "Astres" even when cluing a fem surface like "étoiles").
    inflected = index.inflect(head_lemma, target, prefer_pos=target_pos)
    if not inflected and target_pos in _RELAX_GENDER_FOR:
        inflected = index.inflect(head_lemma, target - GENDER_TOKENS,
                                   prefer_pos=target_pos)
        target = target - GENDER_TOKENS
    if not inflected:
        return InflectionResult(_capitalize_first(clue), "no-inflection")

    new_tokens = list(tokens)
    head_changed = inflected.lower() != tokens[head_idx].lower()
    new_tokens[head_idx] = inflected

    # Adjective agreement. Walk forward from the head; track the current
    # agreement target. In a "head de N adj" / "head à N adj" construction
    # French attaches the adj to the closer noun (`Carnets de notes
    # quotidiennes`), so we update the target when crossing a preposition
    # into a new noun. Pre-head walk uses the head's target only — adj
    # before the head modifies it directly.
    initial = _agreement_target(inflected, head_lemma, target_pos, target, index)
    if initial:
        # Forward walk: state machine NP (after head, before any preposition)
        # vs PP (after crossing into a prepositional phrase). In NP state,
        # adj agrees with the head's target (post-head modifier). On crossing
        # a preposition we enter PP state; the next noun re-anchors the
        # agreement target, and subsequent adj agree with that closer noun
        # ("Carnets de notes quotidiennes").
        agreement = set(initial)
        in_pp = False
        i = head_idx + 1
        while i < len(new_tokens):
            tok = new_tokens[i]
            if not _is_alpha_token(tok):
                break
            lo = tok.lower()
            classes = index.pos_classes_of_form(lo)
            if lo in _AGREEMENT_PASSTHROUGH:
                in_pp = True
                i += 1
                continue
            # In PP state, an encountered noun re-anchors the target.
            if in_pp and "nom" in classes:
                noun_lemma = index.lemma_of_form(lo, prefer_pos="nom")
                if noun_lemma:
                    new_target = _noun_agreement_from_form(lo, noun_lemma, index)
                    if new_target:
                        agreement = new_target
                in_pp = False  # we're now in the inner NP after the prep
                i += 1
                continue
            # Adjective: inflect to current agreement target.
            if "adj" in classes:
                adj_lemma = index.lemma_of_form(lo, prefer_pos="adj")
                if adj_lemma:
                    new_form = index.inflect(adj_lemma, agreement, prefer_pos="adj")
                    if new_form and new_form.lower() != lo:
                        new_tokens[i] = new_form
                i += 1
                continue
            # Coordinating conjunction: walk through to reach co-modifiers.
            # `Long et barbant` → both adj should agree, even though `et` would
            # otherwise break the chain.
            if lo in _COORD_WALKTHROUGH:
                i += 1
                continue
            break

        # Backward walk: pre-head adjectives. Walks through coordinating
        # conjunctions so `Long et barbant` cluing a fem-pl head agrees both
        # adjectives (`Longues et barbantes`).
        i = head_idx - 1
        while i >= 0:
            tok = new_tokens[i]
            if not _is_alpha_token(tok):
                break
            lo = tok.lower()
            if lo in _COORD_WALKTHROUGH:
                i -= 1
                continue
            if lo in _FUNCTION_WORDS:
                break
            if "adj" not in index.pos_classes_of_form(lo):
                break
            adj_lemma = index.lemma_of_form(lo, prefer_pos="adj")
            if not adj_lemma:
                break
            new_form = index.inflect(adj_lemma, initial, prefer_pos="adj")
            if new_form and new_form.lower() != lo:
                new_tokens[i] = new_form
            i -= 1

    if not head_changed and new_tokens == tokens:
        return InflectionResult(_capitalize_first(clue), "identity")

    rebuilt = _detokenize(new_tokens)
    return InflectionResult(_capitalize_first(rebuilt), "")


# Coordinating conjunctions — walk through during agreement so `X et Y`
# co-modifiers both agree.
_COORD_WALKTHROUGH = {"et", "ou", "ni"}


# Tokens we walk through during forward agreement: prepositions, articles,
# elision particles. They link a head to a downstream noun without breaking
# the noun-phrase chain, so we keep walking instead of stopping.
_AGREEMENT_PASSTHROUGH = {
    "de", "d", "à", "au", "aux", "du", "des", "en", "dans", "sur", "sous",
    "par", "pour", "avec", "sans", "le", "la", "les", "un", "une",
    "l", "ce", "cet", "cette", "ces", "son", "sa", "ses", "leur", "leurs",
    "mon", "ma", "mes", "ton", "ta", "tes", "notre", "nos", "votre", "vos",
}


def _noun_agreement_from_form(
    surface: str, lemma: str, index: MorphologyIndex,
) -> set[str] | None:
    """Look up the noun's intrinsic gender + the surface's number."""
    for l, tags in index.lookup_form(surface):
        if l.lower() != lemma.lower():
            continue
        gn = (tags & GENDER_TOKENS) | (tags & NUMBER_TOKENS)
        if "epi" in gn:
            gn = (gn - {"epi"}) | {"mas"}
        if gn:
            return gn
    return None


def _agreement_target(
    inflected_head: str,
    head_lemma: str,
    target_pos: str,
    target: set[str],
    index: MorphologyIndex,
) -> set[str] | None:
    """Return the (gender, number) tag set adjacent adjectives should match.
    None if no agreement applies (e.g. finite verb form — adj near a finite
    verb is unusual and we don't try)."""
    if target_pos == "verbe":
        # Only past participles agree like adjectives.
        if "ppas" not in target:
            return None
    # Find the inflected head's analysis matching the head_lemma. Pull its
    # gender + number tags.
    for lemma, tags in index.lookup_form(inflected_head):
        if lemma.lower() != head_lemma.lower():
            continue
        gn = (tags & GENDER_TOKENS) | (tags & NUMBER_TOKENS)
        if gn:
            # Epicene head → adj should pick mas (default in French).
            if "epi" in gn:
                gn = (gn - {"epi"}) | {"mas"}
            return gn
    return None


# Punctuation tokens that should never have whitespace around them — they
# cling to both sides ("d'objets", "Connaître-le-plus").
_GLUE_BOTH_SIDES = {"'", "’", "-"}
# Punctuation tokens that cling to the previous token only (no space before).
_GLUE_BEFORE = {",", ".", "!", "?", ":", ";", ")", "]"}


def _detokenize(tokens: list[str]) -> str:
    """Glue tokens back together with crossword-style spacing."""
    if not tokens:
        return ""
    parts = [tokens[0]]
    for prev, tok in zip(tokens, tokens[1:]):
        glue_left = tok in _GLUE_BOTH_SIDES or tok in _GLUE_BEFORE
        glue_right = prev in _GLUE_BOTH_SIDES
        if glue_left or glue_right:
            parts.append(tok)
        else:
            parts.append(" " + tok)
    return "".join(parts)
