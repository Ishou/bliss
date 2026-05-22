#!/usr/bin/env python3
"""Lemmatize a surface-inflected mots-fléchés clue.

Dual of `inflect_clue.inflect_clue`: takes a clue whose head token is already
in some surface morphology (e.g. ppas `Possédé`, 3sg ipre `Possède`, passé
composé `S'est désaltéré`) and rewrites it to the citation form (`Posséder`,
`Se désaltérer`). Used by the editorial cross-surface propagation step — once
a clue is in lemma form, the forward inflater can re-emit it for any other
surface of the same lemma's paradigm.

Scope (v1):
- Strip optional leading reflexive (`Se`/`S'`) — restored at output time with
  elision-aware spacing (`Se` before consonant, `S'` before vowel/h).
- Strip optional finite auxiliary (être or avoir conjugated, non-ppas) when
  it immediately follows the (possibly stripped) reflexive. This is the
  passé-composé shape `[Se]? + aux + ppas + trailing`.
- Replace the first remaining content-verb token with its lemma via
  `MorphologyIndex.lemma_of_form(prefer_pos="verbe")`.
- Non-head tokens stay verbatim.

Out of scope:
- Multi-clause / coordinated heads (the forward inflater handles this on
  the reverse leg; we keep lemmatization head-only on purpose).
- Adjective heads (would-be `Vue` → `Voir`?). Treat adj heads as already in
  lemma form (they are, in mots-fléchés convention) and return the clue
  unchanged with `already-lemma` flag.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from morphology_index import MorphologyIndex

_TOKEN_RE = re.compile(r"[\wÀ-ÿŒœŸ]+|[^\s\wÀ-ÿ]+", re.UNICODE)

# Vowels and h that trigger elision in `Se → S'`. Includes common French
# accented vowels. `h` is treated permissively (Bliss editorial corpus
# doesn't contain h-aspiré verb heads).
_ELISION_INITIALS = set("aeiouéèêëàâîïôûùÿœh")

# Words whose noun reading dominates as a clue-opening "meta" marker, but
# whose grammalecte entry ALSO carries verb-conjugation rows (e.g. `forme`
# is both noun-fem and 1sg/3sg ipre of `former`). When such a word leads the
# clue, treat the whole clue as meta-style — the verb later in the clue
# (`Forme d'avoir`, `Variante d'être`) is a complement, not a head we want
# to lemmatize. Hand-rolled because the POS-frequency heuristic is wrong
# for these (former has more verb rows than the noun has, so a count-based
# rule would still pick verb).
_META_PREFIX_TOKENS = {
    "forme", "formes",
    "variante", "variantes",
    "type", "types",
    "sorte", "sortes",
    "espèce", "espèces",
    "modèle", "modèles",
    "marque", "marques",
    "particule", "particules",
    "ancien", "anciens", "ancienne", "anciennes",
    "première", "premier", "premières", "premiers",
    "deuxième", "deuxièmes",
    "troisième", "troisièmes",
    "quatrième", "quatrièmes",
    "cinquième", "cinquièmes",
    "sixième", "sixièmes",
    "septième", "septièmes",
    "huitième", "huitièmes",
    "neuvième", "neuvièmes",
    "dixième", "dixièmes",
    "infinitif", "infinitifs",
    "conjugaison", "conjugaisons",
    "nom", "noms",  # `Nom du verbe X` — meta-style.
}

# Punctuation tokens that should never have whitespace around them.
_GLUE_BOTH_SIDES = {"'", "’", "-"}
_GLUE_BEFORE = {",", ".", "!", "?", ":", ";", ")", "]"}


@dataclass
class LemmatizationResult:
    text: str
    flag: str  # '' | 'already-lemma' | 'meta-style' | 'empty' | 'no-content-verb'


def _is_alpha_token(tok: str) -> bool:
    return bool(re.match(r"^[\wÀ-ÿŒœŸ]+$", tok))


def _capitalize_first(s: str) -> str:
    return s[:1].upper() + s[1:] if s else s


def _is_aux_finite(token: str, index: MorphologyIndex) -> bool:
    """True iff `token` is a finite (non-ppas, non-infinitive) form of
    `être` or `avoir`. These are the auxiliaries used to build compound
    tenses; they sit between an optional reflexive and the content ppas."""
    for lemma, tags in index.lookup_form(token.lower()):
        if lemma.lower() not in ("être", "avoir"):
            continue
        if "ppas" in tags or "infi" in tags:
            continue
        return True
    return False


def _starts_with_elision_trigger(lemma: str) -> bool:
    if not lemma:
        return False
    return lemma[0].lower() in _ELISION_INITIALS


def _detokenize(tokens: list[str]) -> str:
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


def lemmatize_clue(
    clue: str,
    index: MorphologyIndex,
) -> LemmatizationResult:
    tokens = _TOKEN_RE.findall(clue)
    if not tokens:
        return LemmatizationResult("", "empty")

    # Walk leading non-alpha tokens.
    i = 0
    while i < len(tokens) and not _is_alpha_token(tokens[i]):
        i += 1

    # Detect + skip an optional reflexive prefix. Two shapes:
    #   - `Se ...`     → tokens [Se, ...]
    #   - `S' + ...`   → tokens [S, ', ...]   (apostrophe is its own token)
    has_reflexive = False
    if i < len(tokens):
        lo = tokens[i].lower()
        if lo == "se":
            has_reflexive = True
            i += 1
        elif lo == "s" and i + 1 < len(tokens) and tokens[i + 1] in ("'", "’"):
            has_reflexive = True
            i += 2

    # Walk through whitespace/punct after reflexive.
    while i < len(tokens) and not _is_alpha_token(tokens[i]):
        i += 1

    # Optional aux strip — finite être / avoir form.
    if i < len(tokens) and _is_aux_finite(tokens[i], index):
        i += 1
        while i < len(tokens) and not _is_alpha_token(tokens[i]):
            i += 1

    # Find the first verb-tagged token from position i. We do NOT call this
    # on already-skipped aux tokens because (a) the aux strip above moved
    # past them, and (b) être/avoir's ppas (été/eu) are also verbs but they
    # arrive at this point only when they ARE the head (`Été capable`).
    #
    # Two bailouts treat the clue as meta-style:
    # - The leftmost content token is in `_META_PREFIX_TOKENS` (handles
    #   `Forme d'avoir` etc., where grammalecte also tags the noun as a
    #   verb conjugation of an unrelated lemma).
    # - A non-verb CONTENT token (noun, adj) sits between the current
    #   position and the first verb (handles `Petit oiseau d'avoir` style
    #   if it ever shows up — generic POS-based catch).
    verb_idx = -1
    for j in range(i, len(tokens)):
        tok = tokens[j]
        if not _is_alpha_token(tok):
            continue
        lo = tok.lower()
        if lo in _META_PREFIX_TOKENS:
            return LemmatizationResult(_capitalize_first(clue), "meta-style")
        classes = index.pos_classes_of_form(lo)
        if not classes:
            # Function word or unknown — keep scanning.
            continue
        if "verbe" not in classes:
            return LemmatizationResult(_capitalize_first(clue), "meta-style")
        verb_idx = j
        break

    if verb_idx < 0:
        # No verb head — clue is meta-style or an adjective/noun-only
        # construction. Return verbatim. If we previously detected a
        # reflexive prefix, the clue is malformed (reflexive without a
        # verb); we still return verbatim with the meta-style flag rather
        # than throwing, because such inputs aren't in the editorial corpus.
        return LemmatizationResult(_capitalize_first(clue), "meta-style")

    head = tokens[verb_idx]
    head_lemma = index.lemma_of_form(head.lower(), prefer_pos="verbe")
    if not head_lemma:
        return LemmatizationResult(_capitalize_first(clue), "no-content-verb")

    head_changed = head_lemma.lower() != head.lower()

    # If nothing to change and no reflexive prefix was stripped, the clue
    # is already in lemma form.
    if not head_changed and not has_reflexive:
        return LemmatizationResult(_capitalize_first(clue), "already-lemma")

    # Reassemble. Start from the verb head onward (trailing tokens), then
    # splice in the reflexive prefix + infinitive at the front.
    trailing = tokens[verb_idx + 1:]

    new_tokens: list[str] = []
    if has_reflexive:
        if _starts_with_elision_trigger(head_lemma):
            new_tokens.extend(["S", "’"])
        else:
            new_tokens.append("Se")
    new_tokens.append(head_lemma)
    new_tokens.extend(trailing)

    rebuilt = _detokenize(new_tokens)
    return LemmatizationResult(_capitalize_first(rebuilt), "")
