"""Regression: the shipped runtime words-fr.csv must not regress to
known-wrong LoRA outputs.

The LoRA emits semantically wrong-sense clues for a small set of
homograph or rare-meaning lemmas — `cane` ("female duck") getting the
clue "Bâton de marche" (which actually means `canne`, a different
word) is the canonical example. Commit 0401229 (May 9 2026) shipped
hand-authored overrides for these; the May 10 corpus-expansion
pipeline re-run silently regressed `cane`/`canes` because the
generation step doesn't know about manual overrides.

This test is the regression guard: any future pipeline run that
overwrites these specific surface clues with the LoRA's wrong-sense
output trips the test. The fix when it fires is to re-apply the
override in:
  - grid/api/src/main/resources/words/words-fr.csv (runtime)
  - data/eval/production/lemma_clues_raw_pos.csv (feeds build_surface_clues)
  - data/eval/production/lemma_clues_shipped.csv (lockstep / docs)

Long-term, this list should be replaced by a curated override CSV that
`merge_clues_into_wordlist.py` applies last (see follow-up in the
docs/eval logbook). For now, an in-code list is enough — the set is
small and grows slowly.
"""

from __future__ import annotations

import csv
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORDLIST = REPO / "grid" / "api" / "src" / "main" / "resources" / "words" / "words-fr.csv"

# Each entry: (surface, substring-that-must-NOT-appear-in-clue, reason).
# Use a substring match — the LoRA can phrase the wrong-sense in
# several ways ("Bâton", "Bâton de marche", "Bâtons", "Bâtons de
# marche") and we want to catch all variants without enumerating
# every inflection.
KNOWN_WRONG_SENSES = [
    ("cane",  "Bâton", "cane = female duck, NOT canne (walking stick); see commit 0401229"),
    ("canes", "Bâton", "canes = female ducks, NOT cannes (walking sticks); see commit 0401229"),
]


def _clue_for(surface: str) -> str | None:
    with WORDLIST.open(encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            if r.get("word") == surface:
                return r.get("clue") or ""
    return None


def test_no_known_wrong_senses_in_runtime_csv() -> None:
    failures: list[str] = []
    for surface, banned_substring, reason in KNOWN_WRONG_SENSES:
        clue = _clue_for(surface)
        if clue is None:
            failures.append(f"row for surface {surface!r} not found in {WORDLIST}")
            continue
        if banned_substring.lower() in clue.lower():
            failures.append(
                f"surface {surface!r} regressed to clue {clue!r} "
                f"(banned substring {banned_substring!r}): {reason}",
            )
    assert not failures, "\n  ".join(["wrong-sense regressions:", *failures])
