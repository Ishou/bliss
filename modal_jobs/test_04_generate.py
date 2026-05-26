"""Tests unitaires pour 04_generate : prompt + lecture lemmes CSV."""

from __future__ import annotations

import importlib.util
import sys
import textwrap
from pathlib import Path

import pytest


HERE = Path(__file__).resolve().parent
MODULE_PATH = HERE / "04_generate.py"


def _load_module():
    """Charge 04_generate.py — nom-de-fichier non importable directement."""
    spec = importlib.util.spec_from_file_location("mod04", MODULE_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["mod04"] = module
    spec.loader.exec_module(module)
    return module


def test_construire_prompt_format() -> None:
    mod = _load_module()
    prompt = mod.construire_prompt("chat", "definition_directe")
    assert "chat" in prompt
    assert "definition_directe" in prompt
    assert prompt.startswith("Donne une définition de mot fléché pour")


def test_styles_actifs_count() -> None:
    mod = _load_module()
    assert len(mod.STYLES_ACTIFS) == 5
    assert set(mod.STYLES_ACTIFS) == {
        "definition_directe", "periphrase", "culturel",
        "cryptique", "fonction_role",
    }


def test_charger_lemmes_semicolon(tmp_path: Path) -> None:
    mod = _load_module()
    csv_path = tmp_path / "lemmes.csv"
    csv_path.write_text(
        textwrap.dedent(
            """\
            mot;extra
            chat;1
            chien;2
            lapin;3
            """
        ),
        encoding="utf-8",
    )
    assert mod.charger_lemmes(csv_path) == ["chat", "chien", "lapin"]


def test_charger_lemmes_comma(tmp_path: Path) -> None:
    mod = _load_module()
    csv_path = tmp_path / "lemmes.csv"
    csv_path.write_text("mot\nchat\nchien\n", encoding="utf-8")
    assert mod.charger_lemmes(csv_path) == ["chat", "chien"]


def test_charger_lemmes_missing_column(tmp_path: Path) -> None:
    mod = _load_module()
    csv_path = tmp_path / "lemmes.csv"
    csv_path.write_text("word\nchat\n", encoding="utf-8")
    with pytest.raises(ValueError, match="Colonne `mot` manquante"):
        mod.charger_lemmes(csv_path)


def test_charger_lemmes_missing_file(tmp_path: Path) -> None:
    mod = _load_module()
    with pytest.raises(FileNotFoundError):
        mod.charger_lemmes(tmp_path / "nope.csv")
