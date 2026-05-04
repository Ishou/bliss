"""Pixel-fit measurement for mots-fléchés clue cells.

The grid in the API renders Nunito Variable, scaled by container-query
units in `frontend/src/ui/components/grid/Cell.tsx`. Cell size is
proportional to grid size (5×5 cells are 4× larger than 11×11 cells in
the same container), and FitText picks a font size linear in cell
width. Therefore wrap pattern is **zoom-invariant** — once a clue fits
at the reference cell, it fits at every render size.

This module measures the wrap once at a 100 × 100 px reference cell
with font size 18 px (ratio 0.18 — the legibility floor we accept).
The same Nunito TTF that the browser ships powers the measurement, so
PIL's verdict matches the renderer (modulo subpixel rounding ≪ 1 px).

Usage:
    from clue_metrics import fits_single_cell, fits_stacked_cell
    fits_single_cell("Mutation notable")        # True
    fits_stacked_cell("Mammifère carnivore")    # False — wraps too many lines
"""
from __future__ import annotations

from pathlib import Path

from PIL import ImageFont

# Reference cell — chosen for clean numbers. Any actual render size is a
# linear scale of this, so pass/fail at the reference is pass/fail
# everywhere.
REFERENCE_CELL = 100  # px square
RATIO = 0.18          # font / cell — the minimum legibility floor
LINE_H = 1.1          # CSS line-height inside def cells (Cell.tsx:42)
PADDING = 3           # `Cell.tsx defCell` padding 3px on each side

_FONT_PATH = Path(__file__).resolve().parent.parent.parent / \
    "data" / "fonts" / "Nunito-Variable.ttf"
_FONT_SIZE = int(REFERENCE_CELL * RATIO)
_FONT = ImageFont.truetype(str(_FONT_PATH), size=_FONT_SIZE)
_LINE_HEIGHT_PX = _FONT_SIZE * LINE_H

# Available content area at reference size (cell minus padding both sides).
# Single cell: square; stacked half-cell: full width × half height − divider.
SINGLE_W = REFERENCE_CELL - 2 * PADDING
SINGLE_H = REFERENCE_CELL - 2 * PADDING
STACK_W = REFERENCE_CELL - 2 * PADDING
STACK_H = REFERENCE_CELL // 2 - PADDING - 1  # 1px divider between stacked clues


def _text_width(text: str) -> int:
    """Pixel width of `text` rendered at the reference font (no wrapping)."""
    if not text:
        return 0
    bbox = _FONT.getbbox(text)
    return bbox[2] - bbox[0]


def _wrap(text: str, max_width: int) -> list[str]:
    """Greedy word-wrap. Falls back to char-wrap inside a long unbroken
    word that exceeds `max_width` on its own (matches CSS
    `overflowWrap: break-word`)."""
    words = text.split()
    lines: list[str] = []
    current = ""
    for word in words:
        # Word alone too wide → char-break it.
        if _text_width(word) > max_width:
            if current:
                lines.append(current)
                current = ""
            chunk = ""
            for ch in word:
                trial = chunk + ch
                if _text_width(trial) > max_width:
                    if chunk:
                        lines.append(chunk)
                    chunk = ch
                else:
                    chunk = trial
            if chunk:
                current = chunk
            continue
        # Normal word: try to append.
        trial = f"{current} {word}" if current else word
        if _text_width(trial) <= max_width:
            current = trial
        else:
            if current:
                lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def fits(text: str, w: int, h: int) -> bool:
    """True iff `text` wraps to fit in (`w`, `h`) at the reference font."""
    if not text:
        return True
    lines = _wrap(text, w)
    if not lines:
        return True
    # Vertical: number of lines × line-height must fit.
    if len(lines) * _LINE_HEIGHT_PX > h:
        return False
    # Horizontal: every wrapped line must fit (sanity — _wrap already
    # enforced this for normal words; char-break can produce lines whose
    # final character pushes a hair past w due to integer rounding).
    return all(_text_width(line) <= w for line in lines)


def fits_single_cell(text: str) -> bool:
    return fits(text, SINGLE_W, SINGLE_H)


def fits_stacked_cell(text: str) -> bool:
    return fits(text, STACK_W, STACK_H)


if __name__ == "__main__":
    # Quick sanity table.
    samples = [
        "Mutation notable",
        "Mammifère carnivore aquatique",
        "Petit chien",
        "i" * 30,
        "M" * 30,
        "Soutien et assistance apportés à autrui",
        "Tenue pour vrai",
        "Carnets de notes quotidiennes",
    ]
    print(f"{'clue':45s} single  stacked  width(px)")
    for s in samples:
        print(f"  {s!r:43s} {str(fits_single_cell(s)):6s}  "
              f"{str(fits_stacked_cell(s)):7s} {_text_width(s):4d}")
