"""Tests for triage_runtime_feedback.

Locks in the routing taxonomy agreed in the design discussion:
- mauvais_sens / trop_obscur → lemma_clues_runtime (rating=n)
- trop_facile               → lemma_clues_runtime (rating=b)
- inapproprié               → lemma_clues_runtime (rating=n) AND blocklist
- faute_de_français + surface == lemma → lemma_clues_runtime (rating=n)
- faute_de_français + surface != lemma → inflation_bugs
- autre / unknown reason    → unrouted

End-to-end test verifies the script materialises the right files from a
seed CSV with one row of every reason.
"""

from __future__ import annotations

import csv
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

from triage_runtime_feedback import (  # noqa: E402
    RouteDecision,
    route_row,
    triage,
)


# --- pure routing -----------------------------------------------------------


@pytest.mark.parametrize("reason,expected_rating", [
    ("mauvais_sens", "n"),
    ("trop_obscur", "n"),
    ("trop_facile", "b"),
])
def test_simple_reasons_route_to_runtime(reason: str, expected_rating: str) -> None:
    r = route_row(reason, surface_eq_lemma=False)
    assert r == RouteDecision(runtime_rating=expected_rating)


def test_inapproprie_routes_to_runtime_and_blocklist() -> None:
    r = route_row("inapproprié", surface_eq_lemma=False)
    assert r == RouteDecision(runtime_rating="n", blocklist=True)


def test_faute_lemma_form_routes_to_runtime_n() -> None:
    """surface == lemma means the lemma clue itself is ungrammatical —
    real training signal."""
    r = route_row("faute_de_français", surface_eq_lemma=True)
    assert r == RouteDecision(runtime_rating="n")


def test_faute_inflected_form_routes_to_inflation() -> None:
    """surface != lemma means the lemma clue is presumed correct and the
    error is downstream agreement — inflater bug, not training data."""
    r = route_row("faute_de_français", surface_eq_lemma=False)
    assert r == RouteDecision(inflation=True)


@pytest.mark.parametrize("reason", ["autre", "garbage", "", "MAUVAIS_SENS"])
def test_unknown_or_freeform_reasons_route_to_unrouted(reason: str) -> None:
    """Unknown reason → unrouted (manual review). Reasons are case-sensitive
    enums; uppercase variants are treated as unknown rather than coerced."""
    r = route_row(reason, surface_eq_lemma=False)
    assert r == RouteDecision(unrouted=True)


# --- end-to-end -------------------------------------------------------------


def _read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


def _write_feedback(path: Path, rows: list[dict[str, str]]) -> None:
    fields = ["surface", "lemma", "pos", "clue", "reason", "severity",
              "rater_id", "found_at", "source_iter", "grid_id", "notes"]
    with path.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=fields)
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, "") for k in fields})


def test_triage_end_to_end(tmp_path: Path) -> None:
    """One row of every reason → triage materialises four output files
    with the right partition and row counts."""
    feedback = tmp_path / "runtime_feedback.csv"
    _write_feedback(feedback, [
        # mauvais_sens — runtime n
        {"surface": "chat", "lemma": "chat", "pos": "nom",
         "clue": "Bad meaning clue", "reason": "mauvais_sens",
         "rater_id": "manual:t"},
        # trop_facile — runtime b
        {"surface": "aidé", "lemma": "aider", "pos": "verbe",
         "clue": "Prêté main forte", "reason": "trop_facile",
         "rater_id": "manual:t"},
        # inapproprié — runtime n + blocklist
        {"surface": "x", "lemma": "x", "pos": "nom",
         "clue": "Offensive Phrase", "reason": "inapproprié",
         "rater_id": "manual:t"},
        # faute, surface == lemma — runtime n
        {"surface": "tirer", "lemma": "tirer", "pos": "verbe",
         "clue": "Mauvaise grammaire au lemme",
         "reason": "faute_de_français", "rater_id": "manual:t"},
        # faute, surface != lemma — inflation
        {"surface": "tira", "lemma": "tirer", "pos": "verbe",
         "clue": "Extraire", "reason": "faute_de_français",
         "rater_id": "manual:t",
         "notes": "tense disagreement on finite-verb surface"},
        # autre — unrouted
        {"surface": "y", "lemma": "y", "pos": "nom", "clue": "Misc",
         "reason": "autre", "rater_id": "manual:t",
         "notes": "needs review"},
    ])

    out_dir = tmp_path / "out"
    triage(feedback, out_dir)

    runtime = _read_csv(out_dir / "lemma_clues_runtime.csv")
    blocklist = _read_csv(out_dir / "blocklist_clues.csv")
    inflation = _read_csv(out_dir / "inflation_bugs.csv")
    unrouted = _read_csv(out_dir / "runtime_feedback_unrouted.csv")

    # 4 rows go to runtime: mauvais_sens (n), trop_facile (b),
    # inapproprié (n), faute@lemma (n).
    assert len(runtime) == 4
    ratings = sorted(r["rating"] for r in runtime)
    assert ratings == ["b", "n", "n", "n"]

    # 1 entry in blocklist (inapproprié), normalized to lowercase.
    assert len(blocklist) == 1
    assert blocklist[0]["clue"].strip() == "Offensive Phrase"

    # 1 inflation bug (the tira→Extraire case).
    assert len(inflation) == 1
    assert inflation[0]["surface"] == "tira"
    assert inflation[0]["lemma"] == "tirer"

    # 1 unrouted row (autre).
    assert len(unrouted) == 1
    assert unrouted[0]["reason"] == "autre"


def test_triage_preserves_existing_blocklist_entries(tmp_path: Path) -> None:
    """Blocklist is additive across runs — manually-added entries (or
    entries from a previous run that no longer appear in runtime_feedback)
    must survive a re-triage. Other output files are full overwrites."""
    feedback = tmp_path / "runtime_feedback.csv"
    _write_feedback(feedback, [
        {"surface": "x", "lemma": "x", "pos": "nom",
         "clue": "New blocklisted clue", "reason": "inapproprié",
         "rater_id": "manual:t"},
    ])

    out_dir = tmp_path / "out"
    out_dir.mkdir()
    # Pre-existing blocklist entry that is NOT in runtime_feedback.
    (out_dir / "blocklist_clues.csv").write_text(
        "clue,reason,added_at\n"
        "manual entry,inapproprié,2026-04-01\n",
        encoding="utf-8",
    )

    triage(feedback, out_dir)

    rows = _read_csv(out_dir / "blocklist_clues.csv")
    clues = sorted((r["clue"] for r in rows), key=str.lower)
    assert clues == ["manual entry", "New blocklisted clue"]


def test_triage_dedupes_blocklist(tmp_path: Path) -> None:
    """Re-running triage with the same feedback must not duplicate
    blocklist rows. Idempotency is non-negotiable per ADR-0013 §7."""
    feedback = tmp_path / "runtime_feedback.csv"
    _write_feedback(feedback, [
        {"surface": "x", "lemma": "x", "pos": "nom",
         "clue": "Dup Clue", "reason": "inapproprié",
         "rater_id": "manual:t"},
    ])

    out_dir = tmp_path / "out"
    triage(feedback, out_dir)
    triage(feedback, out_dir)
    triage(feedback, out_dir)

    rows = _read_csv(out_dir / "blocklist_clues.csv")
    assert len(rows) == 1
