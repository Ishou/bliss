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

    def inflect(self, lemma: str, target_tags: Iterable[str]) -> str | None:
        target = {t for t in target_tags if not _is_verb_paradigm_tag(t)}
        # Gender: epi (epicene) entries satisfy a mas-or-fem request.
        wants_gender = target & GENDER_TOKENS
        target_no_gender = target - GENDER_TOKENS
        for surface, tags in self.by_lemma.get(lemma.lower(), []):
            if not target_no_gender.issubset(tags):
                continue
            if wants_gender:
                tag_genders = tags & GENDER_TOKENS
                if not (wants_gender & tag_genders or "epi" in tag_genders):
                    continue
            return surface
        return None

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
        that one — useful when disambiguating heterogeneous forms."""
        analyses = self.lookup_form(surface)
        if not analyses:
            return None
        if prefer_pos:
            for lemma, tags in analyses:
                pos = _classify(tags)
                if pos == prefer_pos:
                    return lemma
        return analyses[0][0]


def _classify(tags: frozenset[str]) -> str:
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


