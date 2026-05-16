"""Unit tests for the lemma-anchored admission policy in
import_grammalecte_long_words.py.

Covers is_obscure_tag (pure function, all branches) and
parse_grammalecte_lemma_anchored (smoke test with a synthetic lexique).
"""
from __future__ import annotations

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "clue_generation"))

from import_grammalecte_long_words import is_obscure_tag, parse_grammalecte_lemma_anchored  # noqa: E402


# ---------------------------------------------------------------------------
# is_obscure_tag
# ---------------------------------------------------------------------------

def test_ipsi_blocks() -> None:
    assert is_obscure_tag("ipsi 1s") == "passe-simple"
    assert is_obscure_tag("ipsi 3pl") == "passe-simple"


def test_simp_blocks() -> None:
    assert is_obscure_tag("simp 2s") == "subj-imparfait"
    assert is_obscure_tag("simp 1pl") == "subj-imparfait"


def test_cond_1pl_2pl_blocks() -> None:
    assert is_obscure_tag("cond 1pl") == "cond-1pl-2pl"
    assert is_obscure_tag("cond 2pl") == "cond-1pl-2pl"


def test_cond_singulars_and_3pl_allowed() -> None:
    assert is_obscure_tag("cond 1s") is None
    assert is_obscure_tag("cond 2s") is None
    assert is_obscure_tag("cond 3s") is None
    assert is_obscure_tag("cond 3pl") is None


def test_neutral_tag_allowed() -> None:
    assert is_obscure_tag("ind pres 3s") is None
    assert is_obscure_tag("") is None


# ---------------------------------------------------------------------------
# parse_grammalecte_lemma_anchored — smoke test with synthetic lexique
# ---------------------------------------------------------------------------

_LEXIQUE_HEADER = "Flexion\tLemme\tÉtiquettes\tTotal occurrences\n"
_LEXIQUE_ROWS = (
    # in-corpus lemma, neutral tag → should be admitted
    "aimerais\taimer\tcond 1s\t500\n"
    # out-of-corpus lemma → should be rejected (lemma-not-in-corpus)
    "mangerait\tmanger\tcond 3s\t300\n"
    # in-corpus lemma, obscure tag (passe simple) → should be rejected
    "aimât\taimer\tipsi 3s\t10\n"
    # in-corpus lemma, cond 1pl → should be rejected
    "aimerions\taimer\tcond 1pl\t50\n"
)


def _write_synthetic_lexique(rows: str) -> Path:
    tmp = tempfile.NamedTemporaryFile(
        mode="w", encoding="utf-8", suffix=".txt", delete=False
    )
    tmp.write(_LEXIQUE_HEADER)
    tmp.write(rows)
    tmp.flush()
    return Path(tmp.name)


def test_lemma_anchor_admits_in_corpus_neutral() -> None:
    lex = _write_synthetic_lexique(_LEXIQUE_ROWS)
    in_corpus = {"aimer"}
    surfaces, counters = parse_grammalecte_lemma_anchored(
        lex, in_corpus, length_min=4, length_max=15, min_freq=0
    )
    assert "aimerais" in surfaces
    assert surfaces["aimerais"] == ("aimer", 500)


def test_lemma_anchor_rejects_out_of_corpus() -> None:
    lex = _write_synthetic_lexique(_LEXIQUE_ROWS)
    in_corpus = {"aimer"}  # "manger" not present
    surfaces, counters = parse_grammalecte_lemma_anchored(
        lex, in_corpus, length_min=4, length_max=15, min_freq=0
    )
    assert "mangerait" not in surfaces
    assert counters["lemma-not-in-corpus"] >= 1


def test_lemma_anchor_rejects_obscure_tags() -> None:
    lex = _write_synthetic_lexique(_LEXIQUE_ROWS)
    in_corpus = {"aimer"}
    surfaces, counters = parse_grammalecte_lemma_anchored(
        lex, in_corpus, length_min=4, length_max=15, min_freq=0
    )
    assert "aimât" not in surfaces
    assert "aimerions" not in surfaces
    assert counters["obscure-passe-simple"] >= 1
    assert counters["obscure-cond-1pl-2pl"] >= 1


def test_counters_admitted_matches_output_size() -> None:
    lex = _write_synthetic_lexique(_LEXIQUE_ROWS)
    in_corpus = {"aimer"}
    surfaces, counters = parse_grammalecte_lemma_anchored(
        lex, in_corpus, length_min=4, length_max=15, min_freq=0
    )
    assert counters["admitted"] == len(surfaces)
