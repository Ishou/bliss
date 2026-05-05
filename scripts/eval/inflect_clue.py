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

# Determiners and contracted forms that introduce a direct-object NP after a
# transitive head verb. Used to detect lemma clues of the shape
# `[head_verb] [det] [noun]` — for those clues past-participle inflation is
# unsafe, because a PP head with a stranded direct object is ungrammatical:
# `Percer un trou` → PP `Percée un trou` is wrong (would need a `de`-PP
# rephrase, `Percée d'un trou`, which doesn't always read well).
#
# When the surface form admits non-PP moods on the same row, we fall through
# to one of those (e.g. a syncretic `{ipre, ppas}` row inflates to ipre).
# When the surface is PP-only, we cannot ship the verbal lemma clue at all:
# the synthesized 3sg/3pl indicative-present (`Perce un trou`) describes an
# action, but the answer in the grid is the past participle as an adjective
# describing a state. Action-clue for state-form answer is the wrong category
# (mots-fléchés convention is that `forée` is clued by something glossing
# "drilled, in the state of having been drilled", not "drills").
#
# Adjectival-clue sourcing for these answers is the proper long-term fix and
# is tracked separately. Until then we drop the row from the surface clues
# table, letting the runtime placeholder (the surface form rendered as-is)
# ship in its place — strictly better than a semantically wrong action clue.
#
# Excludes prepositions (`à`, `en`, `de`, …) — those introduce a PP-headed
# complement, not a direct object, and PP inflation works fine for them
# (`Mettre en relation` → `Mise en relation`).
_DIRECT_OBJECT_DETERMINERS = {
    "un", "une", "des", "le", "la", "les", "l",
    "ce", "cet", "cette", "ces",
    "son", "sa", "ses", "leur", "leurs",
    "mon", "ma", "mes", "ton", "ta", "tes",
    "notre", "nos", "votre", "vos",
    # `du` is included: post-head it's predominantly the partitive article
    # (`Causer du tort`, `Boire du vin`) and even when it's the preposition
    # `de+le` (`Détourner du droit chemin`) the fall-through to a finite
    # form is still grammatical. False positives are tolerated.
    "du",
    # NOT included: `au`, `aux` (prepositional contractions of à+le/à+les
    # — these introduce PP complements, not direct objects, and PP
    # inflation works correctly for them: `Mise au jour`, `Allé au marché`).
}

# In French crosswords, the clue's gender doesn't need to match the surface's
# gender for nominal targets — "Arrêt" (mas) is a fine clue for "Halte" (fem).
# Verbs and adjectives must keep agreement; nouns relax to number-only.
_RELAX_GENDER_FOR = {"nom"}

# Tokens we consider "content words" (eligible to be the head).
_CONTENT_POS = {"verbe", "nom", "adj"}

# Words always kept verbatim (function words / common adverbs / prepositions).
# Keeping this list small — anything not here is allowed to be the head.
#
# Note: `non` is included here even though grammalecte tags it `:N:m:i`
# (noun, masc-inv) in addition to `:G:X` (adverb). In a clue starting with
# `Non présent`, `non` is the negation adverb and `présent` is the real
# adj head. Without this exclusion, the head ranker would pick `non` (it's
# leftmost and tagged as a content noun), inflate it to its sole invariable
# form, and the downstream agreement walk would inherit the head's `inv`
# tag — causing every adj after `non` to mis-agree.
_FUNCTION_WORDS = {
    "le", "la", "les", "un", "une", "des", "du", "de", "d",
    "à", "au", "aux", "en", "dans", "sur", "sous", "par", "pour", "avec", "sans",
    "et", "ou", "mais", "donc", "car", "ni", "or",
    "qui", "que", "qu", "dont", "où", "quoi",
    "ce", "cet", "cette", "ces", "ceux", "celle", "celui",
    "son", "sa", "ses", "leur", "leurs", "mon", "ma", "mes", "ton", "ta", "tes",
    "ne", "pas", "plus", "très", "trop", "peu", "bien", "mal", "non",
}

_TOKEN_RE = re.compile(r"[\wÀ-ÿŒœŸ]+|[^\s\wÀ-ÿ]+", re.UNICODE)

# Mood preference for resolving syncretic surface forms. When grammalecte
# emits a single row covering multiple moods or persons (e.g. `unis` is BOTH
# 1sg/2sg ipre AND mas-pl ppas; `accompagne` is 1sg/3sg ipre + 1sg/3sg spre +
# 2sg impe), the union of features is too tight to match against the target
# verb's paradigm if that paradigm splits the same syncretism across separate
# rows (e.g. `associer` has separate rows for `associe` 1sg ipre, `associes`
# 2sg ipre, `associe` 1sg spre, etc.). We decompose the target into the
# cartesian product of (mood, person) and try them in this preference order
# — picking the indicative present over the subjunctive present, the past
# participle over the imperative, etc. This matches what a French speaker
# would render in a mots-fléchés clue.
_MOOD_PREFERENCE = (
    "ipre", "ppas", "ifut", "iimp", "ipsi", "cond",
    "ppre", "spre", "simp", "impe", "infi",
)
# Person preference within a mood. For ambiguous syncretic forms like
# `unis` (1sg+2sg ipre fused on one grammalecte row), 2sg is the more
# natural mots-fléchés rendering ("Associes ensemble" reads as a direct
# imperative-style instruction more often than "Associe ensemble").
# 3sg also outranks 1sg because crosswords typically clue verb forms in
# the 3rd person ("Va vite" → court).
_PERSON_PREFERENCE = ("2sg", "3sg", "3pl", "2pl", "1pl", "1sg")


@dataclass
class InflectionResult:
    text: str
    # '' | 'no-target-pos' | 'no-head' | 'no-inflection' | 'identity'
    # | 'pp-only-skipped'
    #
    # `pp-only-skipped`: surface is PP-only AND the lemma clue has the shape
    # `[verb] [det] [noun]` (transitive head + direct-object NP). Inflating
    # to PP would strand the direct object (`Percée un trou`), and falling
    # back to a finite form (`Perce un trou`) is the wrong semantic category
    # — past participles in mots-fléchés expect an adjectival clue describing
    # a state ("drilled", not "drills"). Caller is expected to drop the row
    # so the runtime ships the surface placeholder instead.
    flag: str


def _decompose_targets(
    target: set[str],
    skip_moods: frozenset[str] = frozenset(),
) -> list[set[str]]:
    """Split a fused-feature target into a priority-ordered list of canonical
    targets, each containing at most one mood and one person. The original
    full target is yielded first (covers the simple case where the head verb's
    paradigm row carries the same syncretic union); then progressively
    relaxed candidates follow.

    `skip_moods` filters out trials whose only mood is in the skip set —
    used to suppress past-participle inflation for transitive verb+DObj
    clues, where a PP head leaves the post-verb direct object stranded as
    ungrammatical (`Percée un trou` instead of the finite `Perce un trou`
    or the rephrased adjectival `Percée d'un trou`).

    Why: grammalecte stores syncretic forms on a single row with the union
    of features (e.g. `unis` carries `{ipre, 1sg, 2sg}` AND `{ppas, mas, pl}`,
    or even fused as `{ipre, spre, 1sg, 3sg, impe, 2sg}` for `-er` 1sg).
    The target verb's paradigm may split the same syncretism (irregular -re
    verbs split ipre/spre on separate rows). Strict superset matching then
    fails. Decomposing lets us match individual canonical features at a
    time."""
    moods = target & MOOD_TOKENS
    persons = target & PERSON_TOKENS
    rest = target - moods - persons

    def _allowed(trial_moods: set[str]) -> bool:
        # Allow if at least one mood survives the skip filter, or there are
        # no mood constraints at all (rest-only fallback).
        if not trial_moods:
            return True
        return bool(trial_moods - skip_moods)

    candidates: list[set[str]] = []
    # Full target: only keep if it carries at least one non-skipped mood
    # (or no moods). When skipping PP and the surface is a "pure" PP row
    # like `{ppas, fem, sg}`, the full target has only `ppas` → drop it.
    if _allowed(moods):
        candidates.append(set(target))

    # Single mood × single person decompositions, ordered by preference.
    # If `skip_moods` filtered every mood out, suppress the cartesian walk
    # entirely — emitting `rest`-only candidates here would leak a
    # mood-less trial that matches the head's first paradigm row (the
    # lemma form), defeating the skip.
    moods_after_skip = moods - skip_moods
    if moods_after_skip or persons:
        ordered_moods = [m for m in _MOOD_PREFERENCE
                         if m in moods_after_skip] or [None]
        # When all original moods were skipped (e.g. pure PP target with
        # `skip_moods={'ppas'}`), don't fall back to a None-mood trial: we
        # genuinely have nothing to emit at this step.
        if moods and not moods_after_skip:
            ordered_moods = []
        ordered_persons = [p for p in _PERSON_PREFERENCE if p in persons] or [None]
        for m in ordered_moods:
            for p in ordered_persons:
                trial = set(rest)
                if m is not None:
                    trial.add(m)
                if p is not None:
                    trial.add(p)
                if trial != target:
                    candidates.append(trial)

    # As a last resort, try with mood-only (drop person constraint). We do
    # NOT fall back to an empty target: that would match the lemma's first
    # row (typically the infinitive / mas-sg), which is a silent identity
    # rather than a real conjugation, and would defeat the `no-inflection`
    # signal callers rely on.
    for m in [m for m in _MOOD_PREFERENCE if m in moods and m not in skip_moods]:
        candidates.append(rest | {m})

    # Dedup while preserving order.
    seen: list[set[str]] = []
    for c in candidates:
        if c not in seen:
            seen.append(c)
    return seen


def _is_alpha_token(tok: str) -> bool:
    return bool(re.match(r"^[\wÀ-ÿŒœŸ]+$", tok))


def _has_direct_object_after_head(
    tokens: list[str],
    head_idx: int,
    index: MorphologyIndex,
) -> bool:
    """True iff the lemma clue has the shape `[head_verb] [det] [noun]`.

    Detects transitive verb + direct-object NP — the case where past-
    participle inflation produces an ungrammatical surface (`Percer un trou`
    → PP `Percée un trou`). The check is intentionally narrow: the token
    immediately after the head must be a determiner from a small closed
    set (article / demonstrative / possessive / partitive `du`), and the
    next alphabetic token must admit a `nom` analysis. Prepositional
    complements (`Mettre en relation`, `Aller au marché`) intentionally
    don't trip it — PP inflation works for those."""
    j = head_idx + 1
    # Skip non-alpha glue (apostrophes around elided determiners are emitted
    # as separate tokens by `_TOKEN_RE`).
    while j < len(tokens) and not _is_alpha_token(tokens[j]):
        j += 1
    if j >= len(tokens):
        return False
    if tokens[j].lower() not in _DIRECT_OBJECT_DETERMINERS:
        return False
    # Find the next content token after the determiner; it must be a noun.
    k = j + 1
    while k < len(tokens) and not _is_alpha_token(tokens[k]):
        k += 1
    if k >= len(tokens):
        return False
    return "nom" in index.pos_classes_of_form(tokens[k].lower())


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
    #
    # For verb targets, the surface row may carry a syncretic union of moods
    # / persons (`unis` is `{ipre, 1sg, 2sg}` ∪ `{ppas, mas, pl}`; `abaisse`
    # is `{ipre, spre, 1sg, 3sg, impe, 2sg}`). The head verb's paradigm may
    # split the same syncretism across separate rows, so a strict superset
    # match against the union fails. `_decompose_targets` walks the cartesian
    # product of (mood, person) in preference order and tries each — that's
    # how `unis → Associes ensemble` resolves: the full target fails (no
    # `associer` row carries both 1sg AND 2sg), then `{ipre, 2sg}` matches
    # `associes`, and we ship that.
    # Suppress past-participle inflation when the clue has a direct-object
    # NP after the head verb. PP head + DObj is ungrammatical in French
    # adjectival usage (`Percée un trou`); a `de`-PP rephrase
    # (`Percée d'un trou`) doesn't always read well either.
    #
    # If the surface admits non-PP moods on the same row (syncretic
    # ipre+ppas etc.), the decomposition picks one of those and we proceed
    # normally. If the surface is PP-only, we surface `pp-only-skipped` so
    # the caller can drop the row entirely — see the InflectionResult.flag
    # docstring for the rationale (semantic category mismatch: action clue
    # for a state-form answer).
    skip_moods: frozenset[str] = frozenset()
    pp_skipped = False
    if (
        target_pos == "verbe"
        and "ppas" in target
        and _has_direct_object_after_head(tokens, head_idx, index)
    ):
        skip_moods = frozenset({"ppas"})
        pp_skipped = True

    inflected = None
    chosen_target = target
    for trial in _decompose_targets(target, skip_moods=skip_moods):
        candidate = index.inflect(head_lemma, trial, prefer_pos=target_pos)
        if candidate:
            inflected = candidate
            chosen_target = trial
            break
    if not inflected and target_pos in _RELAX_GENDER_FOR:
        for trial in _decompose_targets(target - GENDER_TOKENS,
                                        skip_moods=skip_moods):
            candidate = index.inflect(head_lemma, trial, prefer_pos=target_pos)
            if candidate:
                inflected = candidate
                chosen_target = trial
                break

    if not inflected:
        # PP-only surface + DObj clue: the verbal lemma is the wrong category
        # for the answer's state-form semantics. Drop the row.
        if pp_skipped:
            return InflectionResult(_capitalize_first(clue), "pp-only-skipped")
        return InflectionResult(_capitalize_first(clue), "no-inflection")
    target = chosen_target

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
