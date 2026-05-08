"""Tests for validate_clue._find_head.

Covers the reflexive/object-pronoun fix: pronominal-verb clues like
"Se soulever contre l'autorité" must resolve to the infinitive token,
not the leading pronoun.

Also covers the pleonasm gate: clues whose head verb already encodes the
trailing modifier ("Associer ensemble", "Monter en haut") must be flagged
so the LoRA picker tries the next candidate rather than shipping a
verbose tautology.
"""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

from validate_clue import _find_head, _find_pleonasm, validate_lemma_clue  # noqa: E402


class _StubIndex:
    """Empty MorphologyIndex stand-in. The too-long branch in the
    validator short-circuits before touching the index; for clues that
    pass the length gate, this stub returns no surface analyses, so the
    validator's family-leak check is a no-op and the head is whatever
    `_find_head` returned. Sufficient for the tests below."""
    by_lemma: dict[str, list] = {}
    def lookup_form(self, surface: str):  # noqa: D401, ANN001
        return []
    def pos_classes_of_form(self, surface: str):  # noqa: D401, ANN001
        return []
    def lemma_of_form(self, surface: str, prefer_pos: str = ""):  # noqa: D401, ANN001
        return None


def test_too_long_clue_rejected() -> None:
    """30 capital M's overflow the reference cell — validator must
    return `too-long`, not `ok`. This is the upstream gate that prevents
    overlong LoRA outputs from reaching the corpus."""
    r = validate_lemma_clue("M" * 30, "foo", "nom", _StubIndex())
    assert r.flag == "too-long", r


def test_short_clue_passes_length_gate() -> None:
    """A short well-formed clue must NOT be rejected as too-long. (The
    head/leak/POS checks downstream may flag it for other reasons; we
    only care about the length gate here.)"""
    r = validate_lemma_clue("Mutation notable", "change", "nom", _StubIndex())
    assert r.flag != "too-long"


@pytest.mark.parametrize("clue,expected_head", [
    # reflexive pronoun — the fix
    ("Se soulever contre l'autorité", "soulever"),
    ("Se cabrer", "cabrer"),
    # elided reflexive (S')
    ("S'enrichir", "enrichir"),
    # subject pronoun filtered, inflected form remains as head
    ("Nous voyons", "voyons"),
    # plain noun — no function word prefix, unchanged
    ("Bagnole", "Bagnole"),
    # article stripped, content word extracted
    ("Le chien", "chien"),
])
def test_find_head_skips_function_words(clue: str, expected_head: str) -> None:
    assert _find_head(clue) == expected_head


# --- pleonasm gate ----------------------------------------------------------

@pytest.mark.parametrize("clue", [
    # "X ensemble" where X already means "join/unite/bring together".
    # This is the wordsparrow.io regression: lemma=unir got
    # "Associer ensemble" — pleonastic since associer ≡ unir ≡ ensemble-action.
    "Associer ensemble",
    "associer ensemble",
    "Unir ensemble",
    "Joindre ensemble",
    "Réunir ensemble",
    "Rassembler ensemble",
    "Lier ensemble",
    "Relier ensemble",
    "Souder ensemble",
    "Fusionner ensemble",
    "Conjuguer ensemble",
    "Marier ensemble",
    "Allier ensemble",
    "Apparier ensemble",
    "Fédérer ensemble",
    "Confédérer ensemble",
    "Coupler ensemble",
    "Accoupler ensemble",
    "Additionner ensemble",
    "Ajouter ensemble",     # additionner's natural lemma synonym
    "Enchaîner ensemble",
    # directional pleonasms
    "Monter en haut",
    "Grimper en haut",
    "Gravir en haut",
    "Monter vers le haut",
    "Descendre en bas",
    "Tomber en bas",
    "Baisser en bas",
    "Descendre vers le bas",
    "Sortir dehors",
    "Entrer dedans",
    "Avancer en avant",
    "Reculer en arrière",
    # repetition pleonasms
    "Répéter à nouveau",
    "Recommencer à nouveau",
    "Refaire à nouveau",
    "Réitérer à nouveau",
    # temporal pleonasms (lemma form + inflected forms — the inflater
    # propagates the redundant tail across all conjugations, so the gate
    # must match passé simple "Prévit"/"Prévis", imparfait "Prévoyais",
    # etc. Stem-prefix rule handles this provided we strip the longest
    # suffix first ("oir" before "ir").)
    "Prévoir à l'avance",
    "Prévit à l'avance",
    "Prévis à l'avance",
    "Prévoyais à l'avance",
    "Anticiper à l'avance",
    "Planifier à l'avance",
    # reciprocity pleonasms (verb already implies mutual action)
    "S'entraider mutuellement",
    "Coopérer mutuellement",
    "Collaborer mutuellement",
])
def test_find_pleonasm_flags_redundant_phrasings(clue: str) -> None:
    """Pleonasm patterns: head verb whose lemma already encodes the trailing
    adverb/PP. The redundant tail token is returned so callers can log it."""
    leak = _find_pleonasm(clue)
    assert leak is not None, f"missed pleonasm in {clue!r}"


@pytest.mark.parametrize("clue", [
    # "Mettre/Travailler ensemble" — head verb is generic, "ensemble" carries
    # the meaning. These are normal French mots-fléchés clues, NOT pleonasms.
    "Mettre ensemble",
    "Mettent ensemble",
    "Travailler ensemble",
    "Vivre ensemble en paix",
    "Former un ensemble",
    "Part de l'ensemble",   # nominal — "ensemble" is the subject, not redundant
    "Plan d'ensemble",
    # legitimate clue heads happening to share substrings
    "Aller à pied",
    "Faire un voyage",
    "Sortir un son",        # the user's correct ÉMET case — must NOT trip
    "Fait sortir un son",
])
def test_find_pleonasm_passes_legitimate_clues(clue: str) -> None:
    leak = _find_pleonasm(clue)
    assert leak is None, f"false-positive pleonasm on {clue!r}: leak={leak!r}"


def test_validate_lemma_clue_returns_pleonasm_flag() -> None:
    """End-to-end: validator flags 'Associer ensemble' as pleonasm so the
    LoRA picker drops it and tries the next candidate. Without this gate
    the clue ships verbatim under lemma=unir → all 24 unir surface forms
    inherit it."""
    r = validate_lemma_clue("Associer ensemble", "unir", "verbe", _StubIndex())
    assert r.flag == "pleonasm", r
    # head is still extracted so dashboards/logs can show it.
    assert r.head.lower() == "associer"


def test_validate_lemma_clue_clean_verb_clue_still_passes() -> None:
    """Sanity: a non-pleonastic verb lemma clue still passes the gate."""
    r = validate_lemma_clue("Mettre ensemble", "réunir", "verbe", _StubIndex())
    # The stub index has no analyses for 'mettre', so we expect head-not-lemma
    # rather than 'pleonasm'. The point: the pleonasm gate did not trip.
    assert r.flag != "pleonasm", r


# --- blocklist gate ---------------------------------------------------------
#
# `inapproprié` runtime feedback writes the offending clue text to
# `data/eval/blocklist_clues.csv`. The validator consults that set on every
# call and short-circuits with `blocklisted` so a single rude/offensive clue
# can never re-ship via a future model run, even if all other gates pass.

from validate_clue import load_blocklist  # noqa: E402


def test_blocklisted_clue_is_flagged() -> None:
    blocklist = frozenset({"clue inappropriée"})
    r = validate_lemma_clue("Clue inappropriée", "lemma", "nom", _StubIndex(),
                            blocklist=blocklist)
    assert r.flag == "blocklisted", r


def test_blocklist_normalises_case_and_whitespace() -> None:
    """Blocklist matching is case- and whitespace-insensitive — the file
    might hold the canonical form but the clue arrives with capitalisation
    or trailing spaces from the LoRA output."""
    blocklist = frozenset({"clue inappropriée"})
    r = validate_lemma_clue("  CLUE   Inappropriée  ", "lemma", "nom",
                            _StubIndex(), blocklist=blocklist)
    assert r.flag == "blocklisted", r


def test_blocklist_takes_priority_over_other_flags() -> None:
    """A blocklisted clue that would also trip pleonasm must surface as
    blocklisted, not pleonasm. The blocklist is the highest-priority gate."""
    blocklist = frozenset({"associer ensemble"})
    r = validate_lemma_clue("Associer ensemble", "unir", "verbe", _StubIndex(),
                            blocklist=blocklist)
    assert r.flag == "blocklisted", r


def test_no_blocklist_still_works() -> None:
    """The blocklist param is optional; existing call sites that pass no
    blocklist must continue to work unchanged."""
    r = validate_lemma_clue("Mettre ensemble", "réunir", "verbe", _StubIndex())
    assert r.flag != "blocklisted", r


def test_load_blocklist_reads_csv(tmp_path) -> None:
    """load_blocklist reads `clue` column, lowercases + strips, ignores
    empty rows and comment lines. Returns frozenset for cheap membership."""
    p = tmp_path / "blocklist.csv"
    p.write_text(
        "clue,reason,added_at\n"
        "Clue Inappropriée,inapproprié,2026-05-08\n"
        "  Autre Mauvaise Clue  ,inapproprié,2026-05-08\n"
        ",,\n",
        encoding="utf-8",
    )
    bl = load_blocklist(p)
    assert "clue inappropriée" in bl
    assert "autre mauvaise clue" in bl
    assert "" not in bl


def test_load_blocklist_missing_file_returns_empty(tmp_path) -> None:
    bl = load_blocklist(tmp_path / "does-not-exist.csv")
    assert bl == frozenset()
