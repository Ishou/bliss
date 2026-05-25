"""Happy-path unit tests for each pipeline filter."""

from __future__ import annotations

import pytest

from . import filters as F


def _row(mot: str, definition: str, **kw) -> dict:
    base = {
        "mot": mot,
        "definition": definition,
        "pos": kw.get("pos", "nom_commun"),
        "categorie": kw.get("categorie", "autre"),
        "style": kw.get("style", "definition_directe"),
        "force": kw.get("force", "1"),
        "longueur": kw.get("longueur", str(len(definition.split()))),
        "source": kw.get("source", "gold_pilot_v1"),
        "meta": kw.get("meta", ""),
    }
    base.update(kw)
    return base


def test_filter_1_typographiques_accepts_clean_french():
    r = _row("POMME", "Tentation d’Ève")
    out = F.filter_1_typographiques(r)
    assert out.action == "accept", out.reason


def test_filter_3_longueur_accepts_short_clue():
    r = _row("COQ", "Mâle de la basse-cour")
    out = F.filter_3_longueur(r)
    assert out.action == "accept", out.reason


def test_filter_5_auto_reference_accepts_unrelated_substring():
    # "rio" substring inside "carioca" is NOT an auto-reference for RIO
    r = _row("RIO", "Habitant de carioca")
    out = F.filter_5_auto_reference(r)
    assert out.action == "accept", out.reason


def test_filter_6_langue_fr_accepts_well_calibrated_french():
    r = _row("CHAPEAU", "Couvre-chef classique")
    out = F.filter_6_langue_fr(r)
    assert out.action == "accept", out.reason


def test_filter_7_tautologie_accepts_specific_clue():
    r = _row("CHAT", "Félin domestique courant")
    out = F.filter_7_tautologie(r)
    assert out.action == "accept", out.reason


def test_filter_9_stem_leak_accepts_clean_definition():
    """Happy-path : aucun token de la définition ne partage de radical avec le mot."""
    r = _row("CHAT", "Félin domestique courant")
    out = F.filter_9_stem_leak(r)
    assert out.action == "accept", out.reason


def test_filter_10_pleonasm_accepts_clean_definition():
    """Happy-path : la définition ne matche aucun patron pléonastique fermé."""
    r = _row("CHIEN", "Animal fidèle au gardien")
    out = F.filter_10_pleonasm(r)
    assert out.action == "accept", out.reason
