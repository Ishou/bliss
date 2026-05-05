"""Tests for clue_metrics — the pixel-fit guarantee.

Verifies that letter-distribution-aware measurement (PIL on Nunito Variable)
matches our manual reasoning at the reference 100×100 px cell. Adversarial
inputs (all-`M` vs all-`i`) lock the contract that character count is NOT a
sufficient proxy.
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

from clue_metrics import (  # noqa: E402
    REFERENCE_CELL,
    SINGLE_W,
    STACK_H,
    fits_single_cell,
    fits_stacked_cell,
)


def test_reference_cell_geometry_is_sane() -> None:
    """Sanity check on the constants — guards against accidental mutation."""
    assert REFERENCE_CELL == 100
    # Padding-aware content area is non-trivial.
    assert SINGLE_W > 50
    assert STACK_H > 10


def test_empty_clue_fits_trivially() -> None:
    assert fits_single_cell("")
    assert fits_stacked_cell("")


@pytest.mark.parametrize("text", [
    # Real-world short noun clues — must fit both single and stacked.
    "Petit chien",
    "Mutation notable",
    "Carte maîtresse",
])
def test_short_realworld_clues_fit_stacked(text: str) -> None:
    assert fits_single_cell(text)
    assert fits_stacked_cell(text)


@pytest.mark.parametrize("text", [
    # Real-world long noun clues — fit single, too tall for stacked.
    "Mammifère carnivore aquatique",
    "Carnets de notes quotidiennes",
])
def test_medium_clues_fit_single_not_stacked(text: str) -> None:
    assert fits_single_cell(text)
    assert not fits_stacked_cell(text)


def test_adversarial_wide_letters_overflow_single() -> None:
    """30 capital M's overflow even the single-cell budget — the
    character count alone (30) is the same as 30 i's, but the pixel
    width is 4× wider. This is the entire reason we measure pixels."""
    assert not fits_single_cell("M" * 30)


def test_adversarial_narrow_letters_fit_single() -> None:
    """30 i's fit comfortably — same character count, ~4× narrower."""
    assert fits_single_cell("i" * 30)


def test_long_clue_with_narrow_letters_still_fits() -> None:
    """57-char clue but narrow letters that wrap to ≤ 4 lines."""
    text = "Soutien et assistance apportés à autrui"
    assert fits_single_cell(text)


def test_extreme_overflow_rejected() -> None:
    """58-char wide clue does not fit even single."""
    assert not fits_single_cell("Arts de négocier et de traiter avec les nations étrangères")
