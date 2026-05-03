"""Tests for validate_clue._find_head.

Covers the reflexive/object-pronoun fix: pronominal-verb clues like
"Se soulever contre l'autorité" must resolve to the infinitive token,
not the leading pronoun.
"""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

from validate_clue import _find_head  # noqa: E402


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
