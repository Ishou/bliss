"""Tests for clue_metrics — the char-cap gate.

The frontend renders def-cell clue text in Lekton (monospace). Every
French character has the same advance, so the gate is a deterministic
predicate on `len(text)` + the runtime's `smart_line_break` +
greedy word-wrap. No font-metric measurement involved.

Adversarial M-vs-i tests from the prior PIL/Nunito gate are gone —
they were designed to lock in the proportional-font assumption that
no longer holds. Replaced with monospace-symmetry tests below.
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

from clue_metrics import (  # noqa: E402
    GATE_RATIO_FLOOR,
    LEKTON_ADVANCE_RATIO,
    REFERENCE_CELL,
    SINGLE_H,
    SINGLE_W,
    STACK_H,
    chars_per_line_at_floor,
    fits_single_cell,
    fits_stacked_cell,
    smart_line_break,
)


def test_reference_cell_geometry_is_sane() -> None:
    """Sanity check on the constants — guards against accidental mutation."""
    assert REFERENCE_CELL == 100
    assert SINGLE_W > 50
    assert STACK_H > 10


def test_lekton_advance_ratio_is_calibrated() -> None:
    """Locks the calibrated constant. Recalibrate via the procedure in
    `clue_metrics.py`'s docstring if Lekton is ever swapped."""
    assert LEKTON_ADVANCE_RATIO == 0.5


def test_empty_clue_fits_trivially() -> None:
    assert fits_single_cell("")
    assert fits_stacked_cell("")


@pytest.mark.parametrize("text", [
    "Petit chien",
    "Mutation notable",
    "Carte maîtresse",
    "Tenue pour vrai",
])
def test_short_realworld_clues_fit_both(text: str) -> None:
    """Short two-word clues fit single AND stacked at the gate floor."""
    assert fits_single_cell(text)
    assert fits_stacked_cell(text)


@pytest.mark.parametrize("text", [
    # 3-word clue with one inner space inside the longer half: greedy
    # word-wrap produces 3 rendered lines → fits single (max 4) but
    # overflows stacked (max 2).
    "Mammifère carnivore aquatique",
    "Soutien et assistance apportés à autrui",
])
def test_medium_clues_fit_single_not_stacked(text: str) -> None:
    assert fits_single_cell(text)
    assert not fits_stacked_cell(text)


def test_long_unbreakable_word_rejected() -> None:
    """A single word longer than chars_per_line is rejected outright —
    the gate does not model `hyphens: auto`. This is intentional
    conservatism: the runtime would syllabically break the word, but
    we'd rather reject than ship a clue whose render depends on
    browser hyphenation patterns."""
    cap = chars_per_line_at_floor(GATE_RATIO_FLOOR, SINGLE_W)
    long_word = "x" * (int(cap) + 5)
    assert not fits_single_cell(long_word)
    assert not fits_stacked_cell(long_word)


def test_monospace_symmetry() -> None:
    """The whole point of the Lekton swap: same-length clues with
    different glyphs return identical fits-results, regardless of
    whether the glyphs would be wide (M) or narrow (i) under a
    proportional font. This is what lets the gate be deterministic
    on `len(text)`."""
    for n in (10, 20, 30):
        assert fits_single_cell("M" * n) == fits_single_cell("i" * n)
        assert fits_stacked_cell("M" * n) == fits_stacked_cell("i" * n)


def test_extreme_overflow_rejected() -> None:
    """Long descriptive clue overflows even single."""
    text = "Arts de négocier et de traiter avec les nations étrangères"
    assert not fits_single_cell(text)


def test_smart_line_break_single_word() -> None:
    assert smart_line_break("présentation") == ["présentation"]


def test_smart_line_break_balanced_split() -> None:
    """Multi-word splits at the most-balanced space — mirrors the
    runtime `smartLineBreak` in Cell.tsx."""
    assert smart_line_break("Mutation notable") == ["Mutation", "notable"]
    assert smart_line_break("Tenue pour vrai") == ["Tenue", "pour vrai"]


def test_fits_single_superset_of_fits_stacked() -> None:
    """Contract: anything that fits stacked fits single (single-cell
    has at least as much room as a stack half-cell). Used implicitly
    by `build_surface_clues.py` which checks fits_single first then
    sets `compact = fits_stacked_cell`."""
    cases = [
        "",
        "Mot",
        "Petit chien",
        "Mutation notable",
        "Carnets de notes quotidiennes",
        "Mammifère carnivore aquatique",
        "M" * 5,
        "M" * 50,
    ]
    for text in cases:
        if fits_stacked_cell(text):
            assert fits_single_cell(text), f"contract violated: {text!r}"
