"""Char-count gate for mots-fléchés clue cells.

The pipeline applies a single hard cap of `MAX_CLUE_CHARS = 25` chars.
Clues longer than that are dropped at build time; everything ≤25 chars
is allowed through. The renderer (Lekton monospace, see Cell.tsx)
handles fitting at the legibility floor — there is no
build-time wrap/pixel predicate anymore. The previous compact /
stacked-cell distinction has been retired (PR #208), as has the
Lekton-wrap predicate that was rejecting otherwise-fine 18-25 char
clues like "Cérémonie d'intronisation" (PR following iter16 evals).

`fits_single_cell` is preserved as a thin wrapper around the char-cap
check so existing call sites (validate_clue, build_surface_clues)
don't need a code change — only a re-run.
"""
from __future__ import annotations

# Hard upper bound on clue length, in characters. Anything beyond this
# is dropped at build time. 25 was chosen as the largest cap at which
# Lekton clues still fit both single cells and stacked half-cells at
# the legibility floor, eliminating the need for a per-clue compact flag.
MAX_CLUE_CHARS = 25


def fits_single_cell(text: str) -> bool:
    """True iff `text` is within the hard char cap.

    The build-time gate is purely length-based: the renderer
    (`Cell.tsx` with Lekton + FitText) handles wrap and shrink at
    runtime. We don't try to predict wrap in Python anymore — empirically
    it was rejecting valid clues with apostrophes / long unbreakable
    words that the runtime renders fine."""
    return len(text) <= MAX_CLUE_CHARS


if __name__ == "__main__":
    print(f"hard char cap: {MAX_CLUE_CHARS}")
    samples = [
        "Mutation notable",
        "Mammifère carnivore aquatique",
        "Petit chien",
        "i" * 30,
        "M" * 30,
        "Cérémonie d'intronisation",
        "Tenue pour vrai",
        "Carnets de notes quotidiennes",
    ]
    print(f"\n{'clue':45s} fits  len")
    for s in samples:
        print(f"  {s!r:43s} {str(fits_single_cell(s)):5s} {len(s):3d}")
