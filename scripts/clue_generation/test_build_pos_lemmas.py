"""Unit tests for build_pos_lemmas — DBnary lookup + accent preservation."""

from __future__ import annotations

import csv
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

import build_pos_lemmas as bpl  # noqa: E402


def _write_dbnary(tmp_path: Path, rows: list[dict[str, str]]) -> Path:
    """Minimal dbnary_fr.csv fixture with lemma/pos/definition columns."""
    path = tmp_path / "dbnary_fr.csv"
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["lemma", "pos", "definition"])
        w.writeheader()
        w.writerows(rows)
    return path


def _write_words(tmp_path: Path, words: list[str]) -> Path:
    """Build a minimal --words input: one mot per line, with a header line."""
    path = tmp_path / "words.csv"
    path.write_text("mot\n" + "\n".join(words) + "\n", encoding="utf-8")
    return path


def _write_freqs(tmp_path: Path, freqs: dict[str, int]) -> Path:
    """Build a minimal --frequencies input mirroring words-fr.csv's word/frequency columns."""
    path = tmp_path / "words-fr.csv"
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["word", "frequency"])
        w.writeheader()
        w.writerows({"word": word, "frequency": freq} for word, freq in freqs.items())
    return path


def _read_out(path: Path) -> list[tuple[str, str]]:
    """Read the output CSV back into a list of (mot, pos) tuples."""
    with path.open(encoding="utf-8") as f:
        reader = csv.reader(f, delimiter=";")
        next(reader)  # header
        return [(row[0], row[1]) for row in reader]


def test_emits_accented_lemma_from_dbnary(tmp_path, monkeypatch):
    """ASCII input `SOLDE adjectif` recovers DBnary's `soldé` and emits `SOLDÉ;adjectif`."""
    dbnary = _write_dbnary(
        tmp_path,
        [
            {"lemma": "solde", "pos": "noun", "definition": "Excédent comptable."},
            {"lemma": "soldé", "pos": "adjective", "definition": "Vendu au rabais."},
        ],
    )
    words = _write_words(tmp_path, ["SOLDE"])
    out = tmp_path / "out.csv"

    monkeypatch.setattr(
        sys, "argv",
        ["build_pos_lemmas.py",
         "--words", str(words),
         "--dbnary", str(dbnary),
         "--out", str(out),
         "--multi-pos-fraction", "1.0"],
    )
    assert bpl.main() == 0

    rows = _read_out(out)
    assert ("SOLDE", "nom_commun") in rows
    assert ("SOLDÉ", "adjectif") in rows


def test_accent_free_word_unchanged(tmp_path, monkeypatch):
    """`pain` has no accent in DBnary; output stays `PAIN`."""
    dbnary = _write_dbnary(
        tmp_path,
        [{"lemma": "pain", "pos": "noun", "definition": "Aliment de boulangerie."}],
    )
    words = _write_words(tmp_path, ["PAIN"])
    out = tmp_path / "out.csv"
    monkeypatch.setattr(
        sys, "argv",
        ["build_pos_lemmas.py", "--words", str(words), "--dbnary", str(dbnary), "--out", str(out)],
    )
    assert bpl.main() == 0
    assert _read_out(out) == [("PAIN", "nom_commun")]


def test_accented_input_passes_through(tmp_path, monkeypatch):
    """When the input is already `SOLDÉ`, the stripped lookup still matches DBnary."""
    dbnary = _write_dbnary(
        tmp_path,
        [{"lemma": "soldé", "pos": "adjective", "definition": "Vendu au rabais."}],
    )
    words = _write_words(tmp_path, ["SOLDÉ"])
    out = tmp_path / "out.csv"
    monkeypatch.setattr(
        sys, "argv",
        ["build_pos_lemmas.py", "--words", str(words), "--dbnary", str(dbnary), "--out", str(out)],
    )
    assert bpl.main() == 0
    assert _read_out(out) == [("SOLDÉ", "adjectif")]


def test_accented_variant_wins_on_grammalecte_frequency(tmp_path, monkeypatch):
    """Both `riviere` and `rivière` are live DBnary nouns; grammalecte freq breaks the tie to `RIVIÈRE`."""
    dbnary = _write_dbnary(
        tmp_path,
        [
            {"lemma": "riviere", "pos": "noun", "definition": "Orthographe ancienne de rivière."},
            {"lemma": "rivière", "pos": "noun", "definition": "Cours d'eau."},
        ],
    )
    words = _write_words(tmp_path, ["RIVIERE"])
    freqs = _write_freqs(tmp_path, {"rivière": 7_800_000})
    out = tmp_path / "out.csv"
    monkeypatch.setattr(
        sys, "argv",
        ["build_pos_lemmas.py", "--words", str(words), "--dbnary", str(dbnary),
         "--frequencies", str(freqs), "--out", str(out)],
    )
    assert bpl.main() == 0
    assert _read_out(out) == [("RIVIÈRE", "nom_commun")]


def test_desuet_only_variant_dropped(tmp_path, monkeypatch):
    """A lemma whose every sense is désuet-tagged is dropped, leaving the live accented twin to win."""
    dbnary = _write_dbnary(
        tmp_path,
        [
            {"lemma": "mere", "pos": "noun", "definition": "(Vieilli) Forme ancienne."},
            {"lemma": "mère", "pos": "noun", "definition": "Femme qui a enfanté."},
        ],
    )
    words = _write_words(tmp_path, ["MERE"])
    freqs = _write_freqs(tmp_path, {})  # no freq data; single live variant resolves at step 1
    out = tmp_path / "out.csv"
    monkeypatch.setattr(
        sys, "argv",
        ["build_pos_lemmas.py", "--words", str(words), "--dbnary", str(dbnary),
         "--frequencies", str(freqs), "--out", str(out)],
    )
    assert bpl.main() == 0
    assert _read_out(out) == [("MÈRE", "nom_commun")]


def test_unknown_word_reported_to_stderr(tmp_path, monkeypatch, capsys):
    """A word with no DBnary match lands in the uncovered list and is logged."""
    dbnary = _write_dbnary(tmp_path, [])
    words = _write_words(tmp_path, ["XYZQZW"])
    out = tmp_path / "out.csv"
    monkeypatch.setattr(
        sys, "argv",
        ["build_pos_lemmas.py", "--words", str(words), "--dbnary", str(dbnary), "--out", str(out)],
    )
    assert bpl.main() == 0
    assert _read_out(out) == []
    err = capsys.readouterr().err
    assert "uncovered" in err
    assert "XYZQZW" in err
