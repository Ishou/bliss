"""Char-count gate for mots-fléchés clue cells.

The frontend renders def-cell clue text (`Cell.tsx defText`) in **Lekton**,
a monospace whose every Latin / Latin-Extended-A glyph has advance =
0.5 em. Width is therefore exactly:

    line_width_px = chars_per_line × font_size × LEKTON_ADVANCE_RATIO

so the gate is a deterministic predicate on `len(clue)` plus the
runtime's `smartLineBreak` line-balancing — no font-metric measurement,
no PIL, no drift when frontend layout constants move.

The pipeline applies a **single hard cap** of `MAX_CLUE_CHARS = 25`
chars: clues longer than that are dropped at build time regardless of
how they wrap. The previous `compact` distinction (clue fits stacked
half-cell) has been retired — at 25 chars Lekton always fits both the
single cell and the stacked half-cell at the legibility floor, so the
extra column was complicating the schema for no win.

Calibration of LEKTON_ADVANCE_RATIO
-----------------------------------
Run once when the clue font changes:

    python3 -m venv /tmp/font-cal && /tmp/font-cal/bin/pip install fontTools brotli
    /tmp/font-cal/bin/python3 - <<'PY'
    from fontTools.ttLib import TTFont
    f = TTFont('frontend/node_modules/@fontsource/lekton/files/lekton-latin-400-normal.woff2')
    upm = f['head'].unitsPerEm
    advs = {m[0] for m in f['hmtx'].metrics.values() if m[0] > 0}
    # For Lekton-latin + Lekton-latin-ext, every glyph advance equals 500
    # of 1000 upm → 0.5 em. Verified 2026-05-07.
    print(advs, upm)
    PY

If the printed set is not `{500}` and `upm` is not `1000`, recalibrate.
"""
from __future__ import annotations

# Mirror Cell.tsx defCell padding and lineHeight.
PADDING = 3
REFERENCE_CELL = 100
LINE_H = 1.05

# Hard upper bound on clue length, in characters. Anything beyond this
# is dropped at build time. 25 was chosen as the largest cap at which
# Lekton clues still fit both single cells and stacked half-cells at
# the legibility floor, eliminating the need for a per-clue compact flag.
MAX_CLUE_CHARS = 25

# Universal legibility floor for the gate. Intentionally below the
# runtime's Phase-1 floor (`SINGLE_RATIO_MIN = 0.18` in Cell.tsx):
# clues whose longest line needs ratio 0.14–0.17 pass the gate and
# are rendered by Phase-2 bisection at runtime — not Phase 1.
#
# Why 0.14: with Lekton's 0.5 em monospace advance, the chars-per-line
# cap at the floor is `1 / (ratio × 0.5)`. At 0.14, that's
# 14.3 chars/line — wide enough to accept common French long words
# like "informatique" / "présentation" / "historique" on a single
# line, so they don't trip Phase 1 and force the runtime into Phase
# 2's smaller ratios.
GATE_RATIO_FLOOR = 0.14

# Lekton glyph advance / em — see the calibration block in the module
# docstring. Exact for every Latin / Latin-Extended-A character (the
# only ranges loaded in `frontend/src/ui/styles/fonts.css`).
LEKTON_ADVANCE_RATIO = 0.5

SINGLE_W = REFERENCE_CELL - 2 * PADDING        # 94
SINGLE_H = REFERENCE_CELL - 2 * PADDING        # 94


def smart_line_break(text: str) -> list[str]:
    """Port of `smartLineBreak` in `frontend/src/ui/components/grid/Cell.tsx`.

    Multi-word clues are split at the most-balanced space so that the
    longer half is as short as possible. Single-word (or near-single-
    word) clues are returned as one line. The runtime honours the
    resulting `\\n` via `whiteSpace: pre-line`.

    Mirroring this in Python rather than just using `len(text)` is the
    point of the gate — it captures the runtime's actual line layout
    without needing any font measurement."""
    real_words = [w for w in text.split() if len(w) >= 2 and any(c.isalpha() for c in w)]
    if len(real_words) < 2:
        return [text]
    best_idx = -1
    best_max = float("inf")
    for i, ch in enumerate(text):
        if ch != " ":
            continue
        longer = max(i, len(text) - i - 1)
        if longer < best_max:
            best_max = longer
            best_idx = i
    if best_idx < 0:
        return [text]
    return [text[:best_idx], text[best_idx + 1 :]]


def _greedy_wrap(text: str, chars_per_line: float) -> list[str]:
    """Greedy word-wrap to a max char count per line. Mirrors the
    browser's default line-breaking on whitespace inside a CSS box.
    A single word that exceeds `chars_per_line` is returned as one
    line — the runtime would then break it via `hyphens: auto` or
    `overflowWrap: break-word`, but the gate does NOT model that
    (stays conservative; the caller's `len(line) > chars_per_line`
    check rejects)."""
    words = text.split(" ")
    lines: list[str] = []
    current = ""
    for word in words:
        if not current:
            current = word
            continue
        trial = f"{current} {word}"
        if len(trial) <= chars_per_line:
            current = trial
        else:
            lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def fits(text: str, w: int, h: int, ratio_floor: float) -> bool:
    """True iff `text` fits in (w, h) at the floor font in Lekton.

    Algorithm: apply `smart_line_break` (mirrors the runtime's hard
    `\\n` for multi-word clues), then greedy-wrap each logical line
    at spaces — what the browser does by default inside a CSS box
    when content exceeds the line width. The gate intentionally does
    NOT model `hyphens: auto` (would need pyphen); a single word
    exceeding the per-line char cap is rejected outright. Net: the
    gate is conservative — false negatives are safe (rare runtime-
    fittable clue dropped from dataset); false positives would render
    then clip, which we never want."""
    if not text:
        return True
    floor_font = ratio_floor * REFERENCE_CELL
    chars_per_line = w / (floor_font * LEKTON_ADVANCE_RATIO)
    line_height_px = floor_font * LINE_H
    rendered: list[str] = []
    for logical_line in smart_line_break(text):
        rendered.extend(_greedy_wrap(logical_line, chars_per_line))
    if any(len(line) > chars_per_line for line in rendered):
        return False
    if len(rendered) * line_height_px > h:
        return False
    return True


def fits_single_cell(text: str) -> bool:
    """True iff `text` is within the hard char cap and fits a single
    def-cell at the legibility floor."""
    if len(text) > MAX_CLUE_CHARS:
        return False
    return fits(text, SINGLE_W, SINGLE_H, GATE_RATIO_FLOOR)


def chars_per_line_at_floor(ratio_floor: float, w: int = SINGLE_W) -> float:
    """Helper for tests / docs — exposes the derived chars-per-line cap."""
    return w / (ratio_floor * REFERENCE_CELL * LEKTON_ADVANCE_RATIO)


if __name__ == "__main__":
    cap = chars_per_line_at_floor(GATE_RATIO_FLOOR, SINGLE_W)
    print(f"chars/line @ floor ({GATE_RATIO_FLOOR}): {cap:.2f}")
    print(f"hard char cap: {MAX_CLUE_CHARS}")
    floor_font_px = GATE_RATIO_FLOOR * REFERENCE_CELL
    line_h = floor_font_px * LINE_H
    print(f"max lines @ single ({SINGLE_H}px): {SINGLE_H // line_h:.0f}")
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
    print(f"\n{'clue':45s} single  len  lines")
    for s in samples:
        ls = smart_line_break(s)
        print(f"  {s!r:43s} {str(fits_single_cell(s)):6s} {len(s):3d}  {len(ls)}")
