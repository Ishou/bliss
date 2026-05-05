"""Tests for validate_clue._find_head.

Covers the reflexive/object-pronoun fix: pronominal-verb clues like
"Se soulever contre l'autorité" must resolve to the infinitive token,
not the leading pronoun.
"""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

from validate_clue import _find_head, validate_lemma_clue  # noqa: E402


class _StubIndex:
    """Empty MorphologyIndex stand-in. The too-long branch in the
    validator short-circuits before touching the index; for clues that
    pass the length gate, this stub returns no surface analyses, so the
    validator's family-leak check is a no-op and the head is whatever
    `_find_head` returned. Sufficient for the tests below."""
    by_lemma: dict[str, list] = {}
    def lookup_form(self, surface: str):  # noqa: D401, ANN001
        return []
    def pos_classes_of_form(self, surface: str):  # noqa: D401, ANN001
        return []
    def lemma_of_form(self, surface: str, prefer_pos: str = ""):  # noqa: D401, ANN001
        return None


def test_too_long_clue_rejected() -> None:
    """30 capital M's overflow the reference cell — validator must
    return `too-long`, not `ok`. This is the upstream gate that prevents
    overlong LoRA outputs from reaching the corpus."""
    r = validate_lemma_clue("M" * 30, "foo", "nom", _StubIndex())
    assert r.flag == "too-long", r


def test_short_clue_passes_length_gate() -> None:
    """A short well-formed clue must NOT be rejected as too-long. (The
    head/leak/POS checks downstream may flag it for other reasons; we
    only care about the length gate here.)"""
    r = validate_lemma_clue("Mutation notable", "change", "nom", _StubIndex())
    assert r.flag != "too-long"


@pytest.mark.parametrize("clue,expected_head", [
    # reflexive pronoun — the fix
    ("Se soulever contre l'autorité", "soulever"),
    ("Se cabrer", "cabrer"),
    # elided reflexive (S')
    ("S'enrichir", "enrichir"),
    # subject pronoun filtered, inflected form remains as head
    ("Nous voyons", "voyons"),
    # plain noun — no function word prefix, unchanged
    ("Bagnole", "Bagnole"),
    # article stripped, content word extracted
    ("Le chien", "chien"),
])
def test_find_head_skips_function_words(clue: str, expected_head: str) -> None:
    assert _find_head(clue) == expected_head
