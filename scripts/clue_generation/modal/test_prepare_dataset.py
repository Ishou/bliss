"""Unit tests for prepare_dataset.split_stratifie and construire_chat_entry."""

from __future__ import annotations

import csv
import json
from pathlib import Path

import pytest

from . import prepare_dataset as pd


@pytest.fixture
def sample_rows() -> list[dict]:
    """Synthetic 20-row CSV with 4 forces, balanced."""
    rows: list[dict] = []
    for force in ("1", "2", "3", "4"):
        for i in range(5):
            rows.append({
                "mot": f"WORD{force}{i}",
                "definition": f"Definition {force}.{i}",
                "force": force,
                "pos": "nom_commun",
            })
    return rows


def test_split_stratifie_keeps_force_distribution(sample_rows):
    train, val = pd.split_stratifie(sample_rows)
    # round(5 * 0.12) = round(0.6) = 1 → 1 val per force, 4 train
    from collections import Counter
    val_forces = Counter(r["force"] for r in val)
    train_forces = Counter(r["force"] for r in train)
    for force in ("1", "2", "3", "4"):
        assert val_forces[force] == 1, f"force {force}: {val_forces}"
        assert train_forces[force] == 4, f"force {force}: {train_forces}"


def test_split_stratifie_is_reproducible(sample_rows):
    train_a, val_a = pd.split_stratifie(sample_rows)
    train_b, val_b = pd.split_stratifie(sample_rows)
    assert [r["mot"] for r in train_a] == [r["mot"] for r in train_b]
    assert [r["mot"] for r in val_a] == [r["mot"] for r in val_b]


def test_train_val_are_disjoint_and_exhaustive(sample_rows):
    train, val = pd.split_stratifie(sample_rows)
    train_mots = {r["mot"] for r in train}
    val_mots = {r["mot"] for r in val}
    all_mots = {r["mot"] for r in sample_rows}
    assert train_mots.isdisjoint(val_mots)
    assert train_mots | val_mots == all_mots


def test_construire_chat_entry_shape():
    row = {"mot": "POMME", "definition": "Tentation d’Ève"}
    entry = pd.construire_chat_entry(row)
    assert entry == {
        "messages": [
            {"role": "user", "content": "Donne une définition de mot fléché pour POMME."},
            {"role": "assistant", "content": "Tentation d’Ève"},
        ]
    }
