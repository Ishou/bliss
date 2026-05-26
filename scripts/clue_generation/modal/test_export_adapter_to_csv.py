"""Tests for the Modal to CSV bridge."""

from __future__ import annotations

import csv

from . import export_adapter_to_csv as ex


def test_csv_has_required_columns(tmp_path, monkeypatch):
    # Output CSV must have exactly the columns bliss-worker expects.
    monkeypatch.setattr(
        ex,
        "_generate_clues_on_modal",
        lambda lemmas, adapter_path, **kw: [
            {"lemma": lem, "clue": f"Definition de {lem}", "confidence": 0.9}
            for lem in lemmas
        ],
    )
    monkeypatch.setattr(
        ex,
        "_validate_clue",
        lambda clue, lemma: {"status": "accept", "flag": "ok"},
    )

    out_path = tmp_path / "out.csv"
    ex.run(
        lemma_list=["chat", "chien", "oiseau"],
        adapter_path="/adapters/mistral-nemo-pilot-v1",
        model_version="mistral-nemo-pilot-v1",
        source_tag="mistral-nemo-pilot-v1",
        out_path=out_path,
    )
    with out_path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        assert reader.fieldnames == [
            "lemma", "clue_text", "source", "model_version", "confidence",
        ]
        rows = list(reader)
    assert len(rows) == 3
    assert all(r["source"] == "mistral-nemo-pilot-v1" for r in rows)
    assert all(r["model_version"] == "mistral-nemo-pilot-v1" for r in rows)


def test_rejects_dropped_by_pipeline_v2_are_excluded(tmp_path, monkeypatch):
    # DESTRUCTION / "destructeur" gives LCP=8 (> _STEM_LEAK_MIN=5), triggering filter_9.
    monkeypatch.setattr(
        ex,
        "_generate_clues_on_modal",
        lambda lemmas, adapter_path, **kw: [
            {"lemma": "chat", "clue": "Felin domestique courant", "confidence": 0.9},
            {"lemma": "DESTRUCTION", "clue": "Agent destructeur", "confidence": 0.8},
        ],
    )
    out_path = tmp_path / "out.csv"
    ex.run(
        lemma_list=["chat", "DESTRUCTION"],
        adapter_path="/adapters/mistral-nemo-pilot-v1",
        model_version="mistral-nemo-pilot-v1",
        source_tag="mistral-nemo-pilot-v1",
        out_path=out_path,
    )
    with out_path.open(encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    lemmas = {r["lemma"] for r in rows}
    assert "chat" in lemmas
    assert "DESTRUCTION" not in lemmas, (
        "stem-leak case must be filtered by pipeline_v2 filter_9"
    )
