"""Tests for clue_metrics — the char-cap gate.

The pipeline applies a single hard cap of MAX_CLUE_CHARS = 25 chars.
The Lekton wrap predicate has been retired — at the legibility floor,
the runtime renderer handles wrap, and the Python-side wrap math was
flagging valid clues (e.g. "Cérémonie d'intronisation") as too-long.
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

from clue_metrics import MAX_CLUE_CHARS, fits_single_cell  # noqa: E402


def test_max_clue_chars_pinned_at_25() -> None:
    """The hard char cap is part of the data contract (build_surface_clues
    drops anything longer; CSV consumers can rely on it). Bumping requires
    a coordinated change."""
    assert MAX_CLUE_CHARS == 25


def test_empty_clue_fits_trivially() -> None:
    assert fits_single_cell("")


@pytest.mark.parametrize("text", [
    "Petit chien",
    "Mutation notable",
    "Carte maîtresse",
    "Tenue pour vrai",
])
def test_short_realworld_clues_fit(text: str) -> None:
    """Short clues fit a single cell."""
    assert fits_single_cell(text)


def test_clue_at_cap_fits() -> None:
    """Clues at or below the 25-char cap pass."""
    assert fits_single_cell("Petite portion de matière")  # 25 chars
    assert fits_single_cell("Cérémonie d'intronisation")  # 25 chars


def test_clue_over_cap_rejected() -> None:
    """Anything over 25 chars is rejected, regardless of word shapes."""
    text = "Petite portion de matière fine"  # 30 chars
    assert len(text) > MAX_CLUE_CHARS
    assert not fits_single_cell(text)


def test_long_unbreakable_word_within_cap_passes() -> None:
    """A single long word is fine as long as it's within MAX_CLUE_CHARS.
    The previous wrap predicate rejected these; the new pure-char-cap
    gate accepts them — the runtime renderer handles them via Lekton +
    FitText scaling."""
    assert fits_single_cell("Anticonstitutionnel")  # 19 chars
    assert fits_single_cell("Anticonstitutionnelle")  # 21 chars
    assert not fits_single_cell("Anticonstitutionnellement-bis")  # 29 chars, over cap


def test_lekton_glyph_invariance() -> None:
    """All same-length clues fit-or-don't-fit identically. Lekton is
    monospace; the gate is pure length."""
    for n in (10, 20, 25, 26, 30):
        same = fits_single_cell("M" * n)
        assert fits_single_cell("i" * n) == same
        assert fits_single_cell("é" * n) == same
