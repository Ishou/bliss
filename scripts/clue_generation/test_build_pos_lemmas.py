"""Unit tests for build_pos_lemmas — DBnary lookup + accent preservation."""

from __future__ import annotations

import csv
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

import build_pos_lemmas as bpl  # noqa: E402


def _write_dbnary(tmp_path: Path, rows: list[dict[str, str]]) -> Path:
    """Build a minimal data/dbnary/dbnary_fr.csv with the columns load_dbnary reads."""
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


def _read_out(path: Path) -> list[tuple[str, str]]:
    """Read the output CSV back into a list of (mot, pos) tuples."""
    with path.open(encoding="utf-8") as f:
        reader = csv.reader(f, delimiter=";")
        next(reader)  # header
        return [(row[0], row[1]) for row in reader]
