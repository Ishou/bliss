"""Tests for the multi-source manifest-driven corpus builder."""

from __future__ import annotations

from pathlib import Path

import pytest

from . import build_modal_corpus as bc


# Row-filter parser unit tests.
class TestRowFilterParser:
    def test_empty_filter_accepts_any_row(self):
        """Empty expression passes any row."""
        assert bc._apply_row_filter({"a": "x"}, "") is True

    def test_equality_match_passes(self):
        """Equality on literal matches."""
        assert bc._apply_row_filter({"rating": "y"}, "rating == 'y'") is True

    def test_equality_mismatch_fails(self):
        """Equality on literal rejects mismatch."""
        assert bc._apply_row_filter({"rating": "n"}, "rating == 'y'") is False

    def test_inequality_match_passes(self):
        """Inequality on literal passes when row differs."""
        assert bc._apply_row_filter({"clue": "Definition"}, "clue != ''") is True

    def test_inequality_to_empty_string_fails_when_empty(self):
        """Inequality on literal rejects equal row."""
        assert bc._apply_row_filter({"clue": ""}, "clue != ''") is False

    def test_column_to_column_comparison(self):
        """Column references on both sides are honoured."""
        assert bc._apply_row_filter({"word": "x", "clue": "x"}, "clue != word") is False
        assert bc._apply_row_filter({"word": "x", "clue": "y"}, "clue != word") is True

    def test_and_joiner(self):
        """`and` joiner requires all clauses to pass."""
        row = {"rating": "y", "clue": "Definition"}
        assert bc._apply_row_filter(row, "rating == 'y' and clue != ''") is True
        row2 = {"rating": "y", "clue": ""}
        assert bc._apply_row_filter(row2, "rating == 'y' and clue != ''") is False

    def test_unsupported_grammar_raises(self):
        """Disjunction is outside the grammar and rejected."""
        with pytest.raises(ValueError, match="unsupported filter clause"):
            bc._apply_row_filter({"a": "x"}, "a == 'x' or a == 'y'")

    def test_unsupported_operator_raises(self):
        """Non-equality operators are rejected."""
        with pytest.raises(ValueError, match="unsupported filter clause"):
            bc._apply_row_filter({"a": "x"}, "a > 5")

    def test_missing_column_treated_as_empty_string(self):
        """Missing column reads as empty string (matches csv.DictReader)."""
        assert bc._apply_row_filter({}, "rating == ''") is True


# Builder integration tests.
@pytest.fixture
def tmp_corpus_dir(tmp_path: Path) -> Path:
    """Synthetic corpus inputs + manifest, scoped per test."""
    root = tmp_path
    (root / "data" / "curated").mkdir(parents=True)
    (root / "data" / "lora" / "modal_corpus_v1").mkdir(parents=True)
    (root / "data" / "eval").mkdir(parents=True)

    # Gold source: 3 rows with force tag.
    (root / "data" / "curated" / "gold.csv").write_text(
        "mot;definition;force\n"
        "POMME;Tentation d'Ève;1\n"
        "COQ;Mâle de la basse-cour;2\n"
        "AÏ;Paresseux;3\n",
        encoding="utf-8",
    )

    # Silver source: 2 rows without force.
    (root / "data" / "curated" / "silver.csv").write_text(
        "word,clue\n"
        "lait,Boisson blanche\n"
        "pain,Aliment de boulangerie\n",
        encoding="utf-8",
    )

    # Held-out set: 1 lemma to exclude.
    (root / "data" / "eval" / "eval_human.jsonl").write_text(
        '{"lemma": "POMME"}\n',
        encoding="utf-8",
    )

    # Manifest.
    (root / "data" / "lora" / "modal_corpus_v1" / "manifest.toml").write_text(
        """
version = "test"
seed = 42
val_ratio = 0.0
exclude_lemmas_from = "data/eval/eval_human.jsonl"
user_prompt_template = "Donne une définition de mot fléché pour {mot}."

[[sources]]
name = "gold"
path = "data/curated/gold.csv"
tier = "gold"
weight = 4
csv_delimiter = ";"
schema_mapping = { mot = "mot", definition = "definition", force = "force" }
row_filter = ""

[[sources]]
name = "silver"
path = "data/curated/silver.csv"
tier = "silver"
weight = 2
csv_delimiter = ","
schema_mapping = { mot = "word", definition = "clue", force = "" }
row_filter = ""
""",
        encoding="utf-8",
    )

    return root


def test_loads_sources_with_their_delimiter_and_schema(tmp_corpus_dir):
    """Each source loads via its declared delimiter + mapping."""
    rows = bc.load_all_sources(
        tmp_corpus_dir,
        tmp_corpus_dir / "data" / "lora" / "modal_corpus_v1" / "manifest.toml",
    )
    # 2 gold survive × 4 weight = 8 (POMME excluded), + 2 silver × 2 weight = 4 → 12.
    assert len(rows) == 12, [r["mot"] for r in rows]
    assert any(r["mot"] == "lait" and r["definition"] == "Boisson blanche" for r in rows)


def test_excludes_held_out_lemmas(tmp_corpus_dir):
    """Held-out lemmas never appear in loaded rows."""
    rows = bc.load_all_sources(
        tmp_corpus_dir,
        tmp_corpus_dir / "data" / "lora" / "modal_corpus_v1" / "manifest.toml",
    )
    assert not any(r["mot"] == "POMME" for r in rows), \
        "POMME is in eval_human.jsonl and must not appear in the corpus"


def test_weight_replication_exact_counts(tmp_corpus_dir):
    """Each row appears exactly `weight` times."""
    rows = bc.load_all_sources(
        tmp_corpus_dir,
        tmp_corpus_dir / "data" / "lora" / "modal_corpus_v1" / "manifest.toml",
    )
    from collections import Counter
    mot_counts = Counter(r["mot"] for r in rows)
    assert mot_counts["COQ"] == 4
    assert mot_counts["AÏ"] == 4
    assert mot_counts["lait"] == 2
    assert mot_counts["pain"] == 2


def test_rebuild_is_byte_identical(tmp_corpus_dir):
    """Same manifest + inputs → identical JSONL bytes."""
    out_dir = tmp_corpus_dir / "data" / "lora" / "modal_corpus_v1"
    bc.build_corpus(tmp_corpus_dir, out_dir / "manifest.toml", out_dir)
    train_a = (out_dir / "train.jsonl").read_bytes()
    val_a = (out_dir / "val.jsonl").read_bytes()
    (out_dir / "train.jsonl").unlink()
    (out_dir / "val.jsonl").unlink()
    bc.build_corpus(tmp_corpus_dir, out_dir / "manifest.toml", out_dir)
    train_b = (out_dir / "train.jsonl").read_bytes()
    val_b = (out_dir / "val.jsonl").read_bytes()
    assert train_a == train_b
    assert val_a == val_b


def test_held_out_assertion_fires_when_leak_detected(tmp_corpus_dir, monkeypatch):
    """Defense-in-depth assertion catches held-out regressions."""
    out_dir = tmp_corpus_dir / "data" / "lora" / "modal_corpus_v1"
    monkeypatch.setattr(bc, "_load_held_out_lemmas", lambda *a, **k: set())
    with pytest.raises(ValueError, match="held-out lemma"):
        bc.build_corpus(tmp_corpus_dir, out_dir / "manifest.toml", out_dir)
