"""Regression: surface_clues.csv must never ship `no-inflection-finite` rows.

The bug this guards against is the runtime feedback case `tira → "Extraire"`:
  - extraire and soustraire are partially defective in grammalecte (no `ipsi`,
    no `simp`).
  - 16 surfaces of tirer / dérober at those tenses inflated to `no-inflection`
    under the old policy and shipped the lemma-form infinitive — a tense
    disagreement on the live grid.

The fix splits `no-inflection` into a finite-verb sibling (`no-inflection-finite`)
that build_surface_clues drops to `surface_clues_dropped.csv`, reverting the
affected runtime rows to the empty-clue convention.

This test catches the merged-but-not-rebuilt regression: someone hand-edits
surface_clues.csv to re-include a `no-inflection-finite` row, or a future
inflater change reintroduces the lemma fallback for finite tenses.

If this test fires, re-run:
    python scripts/clue_generation/build_surface_clues.py
    python scripts/clue_generation/merge_clues_into_wordlist.py
"""

from __future__ import annotations

import csv
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SHIPPED_SURFACE = REPO / "data" / "eval" / "production" / "surface_clues.csv"


def test_no_no_inflection_finite_in_shipped_surface_csv() -> None:
    if not SHIPPED_SURFACE.exists():
        return  # production output absent (e.g. fresh checkout) — nothing to guard.
    hits: list[tuple[str, str]] = []
    with SHIPPED_SURFACE.open(encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            if r.get("inflection_status") == "no-inflection-finite":
                hits.append((r.get("surface", ""), r.get("clue", "")))
    assert not hits, (
        f"surface_clues.csv ships {len(hits)} no-inflection-finite rows; "
        f"first 5: {hits[:5]}. Re-run build_surface_clues.py + merge."
    )
