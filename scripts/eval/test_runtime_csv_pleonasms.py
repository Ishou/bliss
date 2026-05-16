"""Regression: the shipped runtime words-fr.csv must not contain pleonastic
clues. The wordsparrow.io live grid bug tracked here was that lemma=unir
shipped with clue 'Associer ensemble', visibly redundant on the live grid.

This test guards three things at once:
1. No row in `grid/api/src/main/resources/words/words-fr.csv` trips
   `validate_clue._find_pleonasm`. If a future LoRA run regresses, the
   pipeline's validator gate now catches it; this test catches the
   merged-but-not-validated artifact case (e.g. someone editing the CSV
   directly).
2. The shipped lemma corpus (lemma_clues_shipped.csv) holds zero pleonasm
   rows.
3. The shipped surface tier (surface_clues.csv) holds zero pleonasm rows.

If any of these fail, run:
    python scripts/clue_generation/strip_pleonastic_clues.py
"""

from __future__ import annotations

import csv
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from validate_clue import _find_pleonasm  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
# Corpus moved from grid/api/.../resources to grid/infrastructure/...
# in PR #439. Prefer the current location; fall back to the old one
# only when present (vacuous skip if neither exists).
_NEW_WORDLIST = REPO / "grid" / "infrastructure" / "src" / "main" / "resources" / "words" / "words-fr.csv"
_OLD_WORDLIST = REPO / "grid" / "api" / "src" / "main" / "resources" / "words" / "words-fr.csv"
WORDLIST = _NEW_WORDLIST if _NEW_WORDLIST.exists() else _OLD_WORDLIST
SHIPPED_LEMMA = REPO / "data" / "eval" / "production" / "lemma_clues_shipped.csv"
SHIPPED_SURFACE = REPO / "data" / "eval" / "production" / "surface_clues.csv"


def _scan_pleonasms(path: Path, clue_field: str) -> list[tuple[str, str, str]]:
    """Return (key, clue, leak) for each pleonasm row at `path`. The first
    column is used as a stable identifier for failure messages."""
    if not path.exists():
        return []
    out: list[tuple[str, str, str]] = []
    with path.open(encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        key_field = (reader.fieldnames or [""])[0]
        for r in reader:
            clue = r.get(clue_field, "")
            leak = _find_pleonasm(clue)
            if leak:
                out.append((r.get(key_field, ""), clue, leak))
    return out


def test_runtime_words_csv_has_no_pleonasms() -> None:
    hits = _scan_pleonasms(WORDLIST, "clue")
    assert not hits, (
        f"runtime words-fr.csv ships {len(hits)} pleonasm rows; "
        f"first 5: {hits[:5]}"
    )


def test_shipped_lemma_corpus_has_no_pleonasms() -> None:
    hits = _scan_pleonasms(SHIPPED_LEMMA, "lemma_clue")
    assert not hits, (
        f"lemma_clues_shipped.csv has {len(hits)} pleonasms; "
        f"first 5: {hits[:5]}"
    )


def test_shipped_surface_corpus_has_no_pleonasms() -> None:
    hits = _scan_pleonasms(SHIPPED_SURFACE, "clue")
    assert not hits, (
        f"surface_clues.csv has {len(hits)} pleonasms; "
        f"first 5: {hits[:5]}"
    )
