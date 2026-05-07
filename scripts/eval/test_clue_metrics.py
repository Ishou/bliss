"""Tests for clue_metrics — the char-cap gate.

The frontend renders def-cell clue text in Lekton (monospace). Every
French character has the same advance, so the gate is a deterministic
predicate on `len(text)` + the runtime's `smart_line_break` +
greedy word-wrap. No font-metric measurement involved.

The pipeline applies a hard MAX_CLUE_CHARS = 25 cap on top of the
wrap predicate; the previous compact / stacked-cell distinction has
been retired (at 25 chars Lekton always fits both layouts at the
legibility floor).
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

from clue_metrics import (  # noqa: E402
    GATE_RATIO_FLOOR,
    LEKTON_ADVANCE_RATIO,
    MAX_CLUE_CHARS,
    REFERENCE_CELL,
    SINGLE_H,
    SINGLE_W,
    chars_per_line_at_floor,
    fits_single_cell,
    smart_line_break,
)


def test_reference_cell_geometry_is_sane() -> None:
    """Sanity check on the constants — guards against accidental mutation."""
    assert REFERENCE_CELL == 100
    assert SINGLE_W > 50
    assert SINGLE_H > 50


def test_lekton_advance_ratio_is_calibrated() -> None:
    """Locks the calibrated constant. Recalibrate via the procedure in
    `clue_metrics.py`'s docstring if Lekton is ever swapped."""
    assert LEKTON_ADVANCE_RATIO == 0.5


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
    """Short two-word clues fit a single cell at the gate floor."""
    assert fits_single_cell(text)


def test_clue_at_cap_fits() -> None:
    """A 25-char clue (the cap) still fits when its wrap is well-formed."""
    assert fits_single_cell("Mammifère terrestre car")  # 23 chars
    assert fits_single_cell("Petite portion de matière")  # 25 chars


def test_clue_over_cap_rejected_even_if_wrap_would_fit() -> None:
    """Over the hard char cap, the wrap math is irrelevant — the gate
    rejects on length alone. Catches scenarios where the LoRA emits a
    long clue whose wrap happens to fit (multi-line, narrow words) but
    the data contract says to drop it."""
    # 30-char multi-word clue — would fit the wrap predicate alone.
    text = "Petite portion de matière fine"  # 30 chars
    assert len(text) > MAX_CLUE_CHARS
    assert not fits_single_cell(text)


def test_long_unbreakable_word_rejected() -> None:
    """A single word longer than chars_per_line is rejected — the
    gate does not model `hyphens: auto`. Conservatism is intentional:
    the runtime would syllabically break the word, but we'd rather
    reject than ship a clue whose render depends on browser
    hyphenation patterns."""
    cap = chars_per_line_at_floor(GATE_RATIO_FLOOR, SINGLE_W)
    long_word = "x" * (int(cap) + 5)
    assert not fits_single_cell(long_word)


def test_monospace_symmetry() -> None:
    """The whole point of the Lekton swap: same-length clues with
    different glyphs return identical fits-results, regardless of
    whether the glyphs would be wide (M) or narrow (i) under a
    proportional font. The hard char cap also doesn't care about
    glyph shape."""
    for n in (10, 20, 30):
        assert fits_single_cell("M" * n) == fits_single_cell("i" * n)


def test_extreme_overflow_rejected() -> None:
    """A clue with an unbreakable single word longer than the chars-
    per-line cap is rejected (no space to wrap at, no hyphens:auto).
    Independent of MAX_CLUE_CHARS — exercises the wrap predicate."""
    text = "Anticonstitutionnellement vôtre"  # single word > char cap
    assert not fits_single_cell(text)


def test_smart_line_break_single_word() -> None:
    assert smart_line_break("présentation") == ["présentation"]


def test_smart_line_break_balanced_split() -> None:
    """Multi-word splits at the most-balanced space — mirrors the
    runtime `smartLineBreak` in Cell.tsx."""
    assert smart_line_break("Mutation notable") == ["Mutation", "notable"]
    assert smart_line_break("Tenue pour vrai") == ["Tenue", "pour vrai"]
