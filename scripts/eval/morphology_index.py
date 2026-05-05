#!/usr/bin/env python3
"""Grammalecte lexique → in-memory morphology index.

Provides two lookups:
- `by_form(surface)` returns every (lemma, tag_set) analysis for the surface.
- `inflect(lemma, target_tags)` returns a surface form whose tag set is a
  superset of `target_tags`, or None.

Tags are stored as `frozenset[str]` (e.g. {"v1__t___zz", "ipre", "spre",
"3pl"}). Set semantics give us free disambiguation for forms that are
syntactically ambiguous (e.g. "ipre" + "spre" on `sexualisent`): a target
of {ipre, 3pl} matches; a target of {spre, 3pl} matches the same surface.

License: grammalecte lexique is MPL-2.0.
"""

from __future__ import annotations

from collections.abc import Iterable
from pathlib import Path

# POS classes we care about for inflection.
POS_NOMINAL = {"nom"}
POS_ADJ = {"adj"}
POS_VERB_PREFIX = "v"  # verb tokens start with v0/v1/v2/v3 + suffix
GENDER_TOKENS = {"mas", "fem", "epi"}
NUMBER_TOKENS = {"sg", "pl", "inv"}
MOOD_TOKENS = {"infi", "ipre", "iimp", "ipsi", "ifut", "cond", "spre", "simp", "impe", "ppre", "ppas"}
PERSON_TOKENS = {"1sg", "2sg", "3sg", "1pl", "2pl", "3pl"}

# Tags that are paradigm-specific (verb-group prefix, oddities) — drop them
# from the target signature so the lookup focuses on inflectional features.
def _is_verb_paradigm_tag(tok: str) -> bool:
    return len(tok) >= 3 and tok[0] == "v" and tok[1].isdigit() and "_" in tok


class MorphologyIndex:
    def __init__(self) -> None:
        self.by_lemma: dict[str, list[tuple[str, frozenset[str]]]] = {}
        self.by_form: dict[str, list[tuple[str, frozenset[str]]]] = {}

    @classmethod
    def load(cls, path: Path) -> "MorphologyIndex":
        idx = cls()
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
                surface = cols[f_idx].lower()
                lemma = cols[l_idx].lower()
                # Strip register markers (!, ?, *) so set-membership matches.
                tags = frozenset(t.rstrip("!?*") for t in cols[e_idx].split() if t)
                if not tags:
                    continue
                idx.by_lemma.setdefault(lemma, []).append((surface, tags))
                idx.by_form.setdefault(surface, []).append((lemma, tags))
        return idx

    def lookup_form(self, surface: str) -> list[tuple[str, frozenset[str]]]:
        return self.by_form.get(surface.lower(), [])

    def inflect(self, lemma: str, target_tags: Iterable[str],
                prefer_pos: str = "") -> str | None:
        """Look up an inflected form of `lemma` matching `target_tags`.

        `prefer_pos` ("nom" / "adj" / "verbe") prefers rows with the
        matching POS marker. For adj-target this is critical: `inflect("fin",
        {fem, pl})` would otherwise return the noun-form `fins` (the rows
        iterate noun-first), but `prefer_pos="adj"` prefers the adj-tagged
        row and yields `fines`. For nom-target we prefer rows carrying the
        `nom` tag — that's the common case (most nouns also carry `adj`
        because they have a 4-form paradigm; the `adj` tag in grammalecte
        marks paradigm shape, not POS).
        """
        target = {t for t in target_tags if not _is_verb_paradigm_tag(t)}
        wants_gender = target & GENDER_TOKENS
        wants_number = target & NUMBER_TOKENS
        # Strip gender + number; we re-check them below with `inv`/`epi`
        # compatibility. Without that exception, an invariable past-participle
        # like `pris` (tagged `{ppas, mas, inv}`) wouldn't match a target of
        # `{ppas, mas, sg}` — strict subset fails because `sg ∉ {inv, ...}`.
        # `inv` semantically means "same form for sg AND pl", so it must be
        # accepted for either number request.
        target_core = target - GENDER_TOKENS - NUMBER_TOKENS

        primary: str | None = None
        fallback: str | None = None
        for surface, tags in self.by_lemma.get(lemma.lower(), []):
            if not target_core.issubset(tags):
                continue
            if wants_number:
                tag_numbers = tags & NUMBER_TOKENS
                # `inv` on EITHER side means "either number works": an
                # invariable target (target carries `inv`) accepts sg or pl
                # head forms, and an invariable head form accepts any
                # target number. Also: if the row simply doesn't carry a
                # number tag (e.g. an infinitive), fall through silently.
                if tag_numbers and "inv" not in wants_number and "inv" not in tag_numbers:
                    if not (wants_number & tag_numbers):
                        continue
            if wants_gender:
                tag_genders = tags & GENDER_TOKENS
                # `epi` on EITHER side means "any gender works": an epicene
                # surface (target) accepts mas or fem head forms, and an
                # epicene head form accepts any target gender. This matters
                # for adjectives whose source surface is epi-plural but the
                # head only has mas/fem rows (`abominables` cluing
                # `monstrueux et répugnant` — `monstrueux` is mas-inv,
                # never tagged epi).
                if "epi" not in wants_gender and "epi" not in tag_genders:
                    if not (wants_gender & tag_genders):
                        continue
            if prefer_pos == "adj":
                is_primary = "adj" in tags
            elif prefer_pos == "nom":
                is_primary = "nom" in tags
            elif prefer_pos == "verbe":
                is_primary = any(_is_verb_paradigm_tag(t) for t in tags)
            else:
                is_primary = True
            if is_primary:
                if primary is None:
                    primary = surface
            else:
                if fallback is None:
                    fallback = surface
        return primary or fallback

    def pos_of_form(self, surface: str) -> str:
        """Return 'verbe' / 'nom' / 'adj' / '' — the dominant POS class of the
        surface's analyses (verb wins over nom over adj over none)."""
        return next(iter(self.pos_classes_of_form(surface)), "")

    def pos_classes_of_form(self, surface: str) -> list[str]:
        """Return every POS class the surface admits, ordered verb > nom > adj.
        Many French words are both nom and adj (sauvage, rouge, ...); the
        single-POS view loses information."""
        analyses = self.lookup_form(surface)
        classes: list[str] = []
        if any(_classify(tags) == "verbe" for _, tags in analyses):
            classes.append("verbe")
        if any("nom" in tags for _, tags in analyses):
            classes.append("nom")
        if any("adj" in tags for _, tags in analyses):
            classes.append("adj")
        return classes

    def lemma_of_form(self, surface: str, prefer_pos: str = "") -> str | None:
        """Return the most-likely lemma for the surface form. If `prefer_pos` is
        given (verbe / nom / adj) and there's an analysis matching it, prefer
        that one — useful when disambiguating heterogeneous forms.

        For `prefer_pos="adj"` we accept any row carrying the `adj` tag
        (including past-participle rows of verbs — grammalecte tags `prononcé`
        as both `:Q` ppas AND `:A` adj on the verb's row). Without that, an
        adj-target call on `prononcé` would return the unrelated NOUN lemma
        `prononcé` and yield no fem-sg form, where the intended head is the
        verb `prononcer` whose ppas paradigm includes `prononcée`."""
        analyses = self.lookup_form(surface)
        if not analyses:
            return None
        if prefer_pos == "adj":
            for lemma, tags in analyses:
                if "adj" in tags:
                    return lemma
        elif prefer_pos:
            for lemma, tags in analyses:
                pos = _classify(tags)
                if pos == prefer_pos:
                    return lemma
        return analyses[0][0]


def _classify(tags: frozenset[str]) -> str:
    """Classify a row's primary POS. Grammalecte's `adj` tag marks the
    4-form paradigm (mas/fem × sg/pl), and `nom` marks "can head a noun
    phrase". A pure noun like `table` has nom only; a pure adj like
    `barbant` has adj only; common nouns (chien, tracteur) and
    substantivizable adjs (humain, petit) carry both. We treat presence
    of `nom` as the dominant signal — that's why `chien` is a noun."""
    for t in tags:
        if t.startswith(POS_VERB_PREFIX) and len(t) > 1 and t[1].isdigit():
            return "verbe"
    if tags & POS_NOMINAL:
        return "nom"
    if tags & POS_ADJ:
        return "adj"
    return ""


def normalize_tag(tok: str) -> str:
    """Strip grammalecte register markers (!, ?, *) so set-membership matches."""
    return tok.rstrip("!?*")


def extract_inflection_target(surface_tags: Iterable[str]) -> set[str]:
    """Pick the tags that should be matched on a clue's head token. Drop the
    paradigm-specific verb prefix; keep mood/tense, person, gender, number."""
    cleaned = (normalize_tag(t) for t in surface_tags)
    return {
        t for t in cleaned
        if not _is_verb_paradigm_tag(t)
        and (t in MOOD_TOKENS or t in PERSON_TOKENS or t in GENDER_TOKENS or t in NUMBER_TOKENS)
    }


def classify_surface_pos(surface_tags: Iterable[str]) -> str:
    return _classify(frozenset(normalize_tag(t) for t in surface_tags))


