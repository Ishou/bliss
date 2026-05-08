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


# Determiners that mark a direct-object NP when they immediately follow
# the head verb. `de la` / `de l'` partitives are excluded because they
# wear `de` first, which serves as the bridge that *would* license a
# PP-state reading ("Couvert de lait" → "Couverte de lait").
_DOBJ_DETERMINERS = {
    "le", "la", "les", "l",
    "un", "une", "des",
    "du",  # masc-sg partitive — `du pain` is DObj, not a `de` bridge.
    "ce", "cet", "cette", "ces",
    "son", "sa", "ses",
    "mon", "ma", "mes", "ton", "ta", "tes",
    "leur", "leurs", "notre", "nos", "votre", "vos",
}

# Prepositions that, when they sit between the head verb and a following
# determiner, license a PP-state reading. `Munir d'un trou` PP-inflates to
# `Munie d'un trou`; `Mettre en ordre` to `Mise en ordre`. The verb still
# takes a complement, but the complement is prepositional and survives the
# participial shift.
_PP_BRIDGE_PREPS = {"de", "d", "à", "au", "aux", "en", "dans", "sur",
                    "sous", "par", "pour", "avec", "sans"}


@dataclass
class InflectionResult:
    text: str
    flag: str  # '' | 'no-target-pos' | 'no-head' | 'no-inflection' | 'identity'
               # | 'head-pos-mismatch' | 'pp-only-skipped'
               # | 'pp-reflexive-skipped' | 'empty'


def _has_reflexive_head(tokens: list[str]) -> bool:
    """True iff the clue's first alphabetic token is the reflexive pronoun
    `Se` / `S'`. A reflexive clue head is a perfectly valid mots-fléchés
    pattern (`S'esclaffer → Rire`); it only breaks for PP-as-adjective
    surfaces, where PP-inflating the head produces ungrammatical text
    like `*S'élevée` (the pronoun stranded against a participial reading).
    Non-PP targets keep the reflexive clue intact.
    """
    for tok in tokens:
        if not _is_alpha_token(tok) and tok not in ("'",):
            continue
        norm = tok.lower().rstrip("'")
        return norm in ("se", "s")
    return False


def _has_verb_dobj_frame(tokens: list[str], head_idx: int) -> bool:
    """True iff the clue is shaped `[head_verb] [det] [N]` — verb + direct
    object — without a prepositional bridge between the verb and the
    determiner. Such clues do not PP-inflate to a grammatical state-clue:
    `Percer un trou` cannot become `*Percée un trou` (the direct-object
    slot is not licensed by the past-participle adjectival reading).

    A `de` / `à` / `en` etc. between the head and the determiner moves
    the clue into a PP-friendly shape (`Munir d'un trou` → `Munie d'un
    trou`); those return False.
    """
    n = len(tokens)
    j = head_idx + 1
    while j < n and not _is_alpha_token(tokens[j]):
        j += 1
    if j >= n:
        return False
    nxt = tokens[j].lower().rstrip("'")
    if nxt in _PP_BRIDGE_PREPS:
        return False
    return nxt in _DOBJ_DETERMINERS


def _decompose_targets(target: set[str]) -> list[set[str]]:
    """Split a fused-feature target into a priority-ordered list of canonical
    targets, each containing at most one mood and one person. The original
    full target is yielded first (covers the simple case where the head verb's
    paradigm row carries the same syncretic union); then progressively
    relaxed candidates follow.

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

    candidates: list[set[str]] = [set(target)]

    # Single mood × single person decompositions, ordered by preference.
    if moods or persons:
        ordered_moods = [m for m in _MOOD_PREFERENCE if m in moods] or [None]
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
    for m in [m for m in _MOOD_PREFERENCE if m in moods]:
        candidates.append(rest | {m})

    # Dedup while preserving order.
    seen: list[set[str]] = []
    for c in candidates:
        if c not in seen:
            seen.append(c)
    return seen


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

    # PP+DObj guard: when the surface is a past participle adjectival use
    # (`forée`, `accordée`) and the lemma clue is shaped `verb + DObj`
    # (`Percer un trou`, `Donner une faveur`), participial inflation strands
    # the direct object (`*Percée un trou`). Iter13's DPO corpus reduces this
    # frame in LoRA output, but residual cases still arrive here. Returning
    # a structural flag (rather than a broken inflate or a 3sg/3pl-fallback
    # action-clue) lets `build_surface_clues.py` route the row to dropped
    # so the runtime keeps the placeholder.
    if (target_pos == "verbe" and "ppas" in target
            and _has_verb_dobj_frame(tokens, head_idx)):
        return InflectionResult(_capitalize_first(clue), "pp-only-skipped")

    # PP+Reflexive guard: a reflexive lemma clue (`Se déplacer`, `S'élever`)
    # is a valid mots-fléchés pattern for verb-form answers (`S'esclaffer →
    # Rire`), but PP-inflating it for a past-participle adjectival surface
    # produces stranded-pronoun text (`*Se déplacée`, `*S'élevée`). Skip
    # to placeholder; the corpus-side preference (DPO theme 1) reduces the
    # rate at which this fires, but the inflater stays defensive in case.
    if (target_pos == "verbe" and "ppas" in target
            and _has_reflexive_head(tokens)):
        return InflectionResult(_capitalize_first(clue), "pp-reflexive-skipped")

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
    inflected = None
    chosen_target = target
    for trial in _decompose_targets(target):
        candidate = index.inflect(head_lemma, trial, prefer_pos=target_pos)
        if candidate:
            inflected = candidate
            chosen_target = trial
            break
    if not inflected and target_pos in _RELAX_GENDER_FOR:
        for trial in _decompose_targets(target - GENDER_TOKENS):
            candidate = index.inflect(head_lemma, trial, prefer_pos=target_pos)
            if candidate:
                inflected = candidate
                chosen_target = trial
                break
    if not inflected:
        return InflectionResult(_capitalize_first(clue), "no-inflection")
    target = chosen_target

    new_tokens = list(tokens)
    head_changed = inflected.lower() != tokens[head_idx].lower()
    new_tokens[head_idx] = inflected

    # Forward walk after the head. Two jobs in one loop:
    #
    #   (a) Co-head inflation: a token whose POS matches `target_pos`,
    #       reached through coordinating conjunctions in NP state, gets
    #       inflated to the head's chosen target morphology. This is what
    #       turns "Nettoyer et désinfecter" → "Nettoie et désinfecte"
    #       for an ipre-3sg surface, and "Élan et énergie" →
    #       "Élans et énergies" for a mas-pl noun surface. Adjectives
    #       fall under this rule too — `target_pos == "adj"` makes
    #       `"adj" in classes` overlap with the co-head branch and gives
    #       the historic `Long et ennuyeux → Longue et ennuyeuse`
    #       behaviour for free.
    #   (b) Post-head adjective agreement: an adj token, regardless of
    #       target POS, agrees with the current `gn` target. Crossing
    #       a preposition (`_AGREEMENT_PASSTHROUGH`) flips to PP state;
    #       the next noun re-anchors `gn` (`Carnets de notes quotidiennes`).
    #
    # Verb targets that don't have a (gender, number) — finite tenses,
    # not ppas — still run the loop for co-head inflation; the adj
    # branch is just a no-op when `gn` is None.
    gn = _agreement_target(inflected, head_lemma, target_pos, target, index)
    initial_gn = gn
    in_pp = False
    saw_coord = False
    i = head_idx + 1
    while i < len(new_tokens):
        tok = new_tokens[i]
        if not _is_alpha_token(tok):
            break
        lo = tok.lower()
        classes = index.pos_classes_of_form(lo)
        # Coordinating conjunction: walk through to the next content token.
        if lo in _COORD_WALKTHROUGH:
            saw_coord = True
            i += 1
            continue
        if lo in _AGREEMENT_PASSTHROUGH:
            in_pp = True
            saw_coord = False
            i += 1
            continue
        # In PP state, an encountered noun re-anchors the agreement target
        # for downstream adjectives (`Carnets de notes quotidiennes`).
        if in_pp and "nom" in classes:
            noun_lemma = index.lemma_of_form(lo, prefer_pos="nom")
            if noun_lemma:
                new_target = _noun_agreement_from_form(lo, noun_lemma, index)
                if new_target:
                    gn = new_target
            in_pp = False
            i += 1
            continue
        if lo in _FUNCTION_WORDS:
            break
        # Co-head: same POS as target, NP state, reached after a conjunction.
        if not in_pp and target_pos in classes and saw_coord:
            co_lemma = index.lemma_of_form(lo, prefer_pos=target_pos)
            if co_lemma:
                co_form = None
                for trial in _decompose_targets(target):
                    cand = index.inflect(co_lemma, trial, prefer_pos=target_pos)
                    if cand:
                        co_form = cand
                        break
                if co_form is None and target_pos in _RELAX_GENDER_FOR:
                    for trial in _decompose_targets(target - GENDER_TOKENS):
                        cand = index.inflect(co_lemma, trial, prefer_pos=target_pos)
                        if cand:
                            co_form = cand
                            break
                if co_form and co_form.lower() != lo:
                    new_tokens[i] = co_form
            saw_coord = False
            i += 1
            continue
        saw_coord = False
        # Post-head adjective agreement (when we know a gn target).
        if gn is not None and "adj" in classes:
            adj_lemma = index.lemma_of_form(lo, prefer_pos="adj")
            if adj_lemma:
                new_form = index.inflect(adj_lemma, gn, prefer_pos="adj")
                if new_form and new_form.lower() != lo:
                    new_tokens[i] = new_form
            i += 1
            continue
        break

    # Backward walk: pre-head co-heads / adjectives. Symmetry with the
    # forward walk — same POS as target gets inflated to head's morphology;
    # adj at any state agrees to the head's `initial_gn`.
    saw_coord = False
    i = head_idx - 1
    while i >= 0:
        tok = new_tokens[i]
        if not _is_alpha_token(tok):
            break
        lo = tok.lower()
        if lo in _COORD_WALKTHROUGH:
            saw_coord = True
            i -= 1
            continue
        if lo in _FUNCTION_WORDS:
            break
        classes = index.pos_classes_of_form(lo)
        if target_pos in classes and saw_coord:
            co_lemma = index.lemma_of_form(lo, prefer_pos=target_pos)
            if co_lemma:
                co_form = None
                for trial in _decompose_targets(target):
                    cand = index.inflect(co_lemma, trial, prefer_pos=target_pos)
                    if cand:
                        co_form = cand
                        break
                if co_form and co_form.lower() != lo:
                    new_tokens[i] = co_form
            saw_coord = False
            i -= 1
            continue
        if initial_gn is not None and "adj" in classes:
            adj_lemma = index.lemma_of_form(lo, prefer_pos="adj")
            if adj_lemma:
                new_form = index.inflect(adj_lemma, initial_gn, prefer_pos="adj")
                if new_form and new_form.lower() != lo:
                    new_tokens[i] = new_form
            i -= 1
            continue
        break

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
