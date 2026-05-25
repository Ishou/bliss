#!/usr/bin/env python3
"""Tests négatifs : vérifier que chaque filtre §8.3 et normalisation §8.4 se déclenche sur des entrées défectueuses."""

from __future__ import annotations

import sys
import unicodedata
from pathlib import Path

from . import filters as F
from . import normalizers as N
from .run_pipeline import (
    traiter_ligne, VALID_POS, VALID_CATEGORIES, VALID_STYLES,
)


def make_row(mot: str, definition: str, *, pos: str = "nom_commun",
             categorie: str = "autre", style: str = "definition_directe",
             force=2) -> dict:
    """Construit un dict ligne de test avec valeurs par défaut valides."""
    return {
        "mot": mot,
        "definition": definition,
        "pos": pos,
        "categorie": categorie,
        "style": style,
        "force": str(force),
        "longueur": str(len(mot)),
        "source": "test",
        "meta": "",
    }


results: list[tuple[str, str, str, str]] = []  # (id, status, target, detail)


def run_filter_test(test_id: str, row: dict, expected_filter: str,
                    expected_action: str, description: str) -> bool:
    """Vérifie qu'un filtre déclenche l'action attendue ("accept" | "reject" | "warning")."""
    out = traiter_ligne(row)
    trace = out["_traces"].get(expected_filter, {})
    actual_action = trace.get("action", "MISSING")
    passed = actual_action == expected_action
    status = "OK  " if passed else "FAIL"
    results.append((test_id, status, expected_filter,
                    f"expected={expected_action}, got={actual_action}"))
    print(f"[{status}] {test_id:6s}  {expected_filter:32s}  "
          f"expected={expected_action:8s}  got={actual_action:8s}  "
          f"— {description}")
    return passed


def run_norm_test(test_id: str, norm_idx: int, input_def: str,
                  expected_output: str, description: str) -> bool:
    """Vérifie qu'une normalisation produit la sortie attendue (appel direct, hors pipeline)."""
    name, fn = N.NORMALIZATIONS[norm_idx]
    result, applied = fn(input_def)
    passed = result == expected_output
    status = "OK  " if passed else "FAIL"
    results.append(
        (test_id, status, name,
         f"in={input_def!r} → out={result!r} (expected {expected_output!r})")
    )
    print(f"[{status}] {test_id:6s}  {name:32s}  "
          f"input={input_def!r:34s} → output={result!r:34s}  "
          f"— {description}")
    return passed


# Tests des filtres §8.3

print("=" * 96)
print("FILTRES §8.3")
print("=" * 96)

# --- Filtre 1 — typographiques ---
run_filter_test("F1-1",
                make_row("PAIN", "Aliment 🍞 de boulangerie"),
                "filter_1_typographiques", "reject",
                "emoji 🍞 dans la définition")
run_filter_test("F1-2",
                make_row("TRAIN", "Transport **ferroviaire**"),
                "filter_1_typographiques", "reject",
                "gras markdown **")
run_filter_test("F1-3",
                make_row("LAMPE", "<b>Source d'éclairage</b>"),
                "filter_1_typographiques", "reject",
                "balise HTML <b>")

# --- Filtre 3 — longueur ---
run_filter_test(
    "F3-1",
    make_row("BAGUETTE",
             "Pain français long magnifique merveilleux extraordinaire "
             "ancestral typique délicieux croustillant frais chaud"),
    "filter_3_longueur", "reject",
    "> 12 mots et > 60 chars",
)
run_filter_test(
    "F3-2",
    make_row("CŒUR",
             "Organe central humain qui bat sang chaud rouge vivant"),
    "filter_3_longueur", "warning",
    "9 mots (> 8, ≤ 12) et ≤ 60 chars",
)

# --- Filtre 4 — stéréotypes IA ---
run_filter_test("F4-1",
                make_row("AIMER", "Action de chérir quelqu'un"),
                "filter_4_stereotypes_ia", "reject",
                "préfixe « Action de »")
run_filter_test("F4-2",
                make_row("BERGER", "Quelqu'un qui garde les moutons"),
                "filter_4_stereotypes_ia", "reject",
                "préfixe « Quelqu'un qui »")
run_filter_test("F4-3",
                make_row("MARTEAU", "Chose qui sert à frapper"),
                "filter_4_stereotypes_ia", "reject",
                "préfixe « Chose qui »")

# --- Filtre 5 — auto-référence ---
run_filter_test("F5-1",
                make_row("POMME", "Une pomme rouge"),
                "filter_5_auto_reference", "reject",
                "match exact « pomme »")
run_filter_test("F5-2",
                make_row("POMME", "Délicieuse fruit pommé"),
                "filter_5_auto_reference", "reject",
                "match via accent strip (pommé → pomme)")
run_filter_test("F5-3",
                make_row("RIO", "Port carioca"),
                "filter_5_auto_reference", "accept",
                "PAS de faux positif (carioca ≠ boundary rio)")
run_filter_test("F5-4",
                make_row("LOU", "Loup sans p",
                         style="cryptique_morphologique"),
                "filter_5_auto_reference", "accept",
                "exception style cryptique_morphologique")

# --- Filtre 6 — langue FR ---
run_filter_test("F6-1",
                make_row("CHAT", "The friendly cat at home"),
                "filter_6_langue_fr", "reject",
                "anglais détecté (the, at, …)")
run_filter_test("F6-2",
                make_row("PAIN", "Bread of the bakery"),
                "filter_6_langue_fr", "reject",
                "anglais détecté (of, the)")

# --- Filtre 7 — tautologie ---
run_filter_test("F7-1",
                make_row("CHAT", "Animal"),
                "filter_7_tautologie", "reject",
                "étiquette pure « Animal »")
run_filter_test("F7-2",
                make_row("CHAT", "Animal commun"),
                "filter_7_tautologie", "warning",
                "étiquette + qualificatif faible « Animal commun »")

# --- Filtre 8 — LLM-juge mock (enum check) ---
run_filter_test("F8-1",
                make_row("PAIN", "Aliment de base", pos="invalid_pos"),
                "filter_8_llm_juge_mock", "reject",
                "pos invalide")
run_filter_test("F8-2",
                make_row("PAIN", "Aliment de base",
                         style="invalid_style"),
                "filter_8_llm_juge_mock", "reject",
                "style invalide")
run_filter_test("F8-3",
                make_row("PAIN", "Aliment de base", force=10),
                "filter_8_llm_juge_mock", "reject",
                "force hors plage [1,5]")


# Tests des normalisations §8.4

print()
print("=" * 96)
print("NORMALISATIONS §8.4 (appel direct, hors pipeline)")
print("=" * 96)

# N1 : apostrophe droite → typographique
run_norm_test("N1", 0,
              "Forme d'avoir", "Forme d’avoir",
              "apostrophe ' → ’")

# N2 : espaces multiples
run_norm_test("N2", 1,
              "Aliment  de  boulangerie", "Aliment de boulangerie",
              "espaces doubles compressés")

# N3 : trim
run_norm_test("N3", 2,
              "  Aliment de boulangerie  ",
              "Aliment de boulangerie",
              "trim début/fin")

# N4 : initiale majuscule
run_norm_test("N4", 3,
              "aliment de boulangerie",
              "Aliment de boulangerie",
              "initiale capitalisée")

# N5 : point final
run_norm_test("N5", 4,
              "Aliment de boulangerie.",
              "Aliment de boulangerie",
              "point final supprimé")

# N6 : guillemets enveloppants
run_norm_test("N6", 5,
              "« Aliment de boulangerie »",
              "Aliment de boulangerie",
              "guillemets français enveloppants supprimés")

# N7 : NFC
nfd_input = unicodedata.normalize("NFD", "Épaule")
nfc_expected = unicodedata.normalize("NFC", "Épaule")
run_norm_test("N7", 6,
              nfd_input, nfc_expected,
              "NFD → NFC (combining marks fusionnés)")

# N8 : tabs/newlines internes
run_norm_test("N8", 7,
              "Aliment\nde boulangerie",
              "Aliment de boulangerie",
              "retour ligne interne remplacé")


# Récap

print()
print("=" * 96)
nb_pass = sum(1 for r in results if r[1] == "OK  ")
nb_fail = sum(1 for r in results if r[1] == "FAIL")
print(f"RÉCAP : {nb_pass}/{len(results)} tests passés "
      f"({nb_fail} échec(s))")
print("=" * 96)

if nb_fail > 0:
    print("\nDétail des échecs :")
    for tid, status, target, detail in results:
        if status == "FAIL":
            print(f"  [FAIL] {tid:6s}  {target:32s}  {detail}")
    sys.exit(1)


# filtres 9 + 10


def test_filter_9_stem_leak_rejects_lcp_5_chars():
    """LCP de 8 entre 'destructeur' et 'destruction' ≥ seuil 5 → reject."""
    r = make_row("DESTRUCTION", "Agent destructeur")
    out = F.filter_9_stem_leak(r)
    assert out.action == "reject"
    assert "stem-leak" in out.reason.lower()


def test_filter_9_stem_leak_accepts_4_char_prefix_share():
    """'Prés' (4 chars) sous le seuil 5 → accept (skip token court)."""
    r = make_row("PRESIDENT", "Prés du sénat")
    out = F.filter_9_stem_leak(r)
    assert out.action == "accept", out.reason


def test_filter_9_stem_leak_rejects_substring_in_long_lemma():
    """'couvert' substring de 'couverture' (les deux ≥ 5) → reject."""
    r = make_row("COUVERTURE", "Mettre un couvert")
    out = F.filter_9_stem_leak(r)
    assert out.action == "reject"
    assert "stem-leak" in out.reason.lower()


def test_filter_9_stem_leak_accepts_short_target_below_threshold():
    """Target 'CHAT' (4 chars) sous le seuil 5 → skip, accept."""
    r = make_row("CHAT", "Félin domestique courant")
    out = F.filter_9_stem_leak(r)
    assert out.action == "accept", out.reason


def test_filter_10_pleonasm_rejects_associer_ensemble():
    """'Associer ensemble' : head='Associer' ∈ rule 1, tail 'ensemble' → reject."""
    r = make_row("UNIR", "Associer ensemble",
                 pos="verbe_infinitif")
    out = F.filter_10_pleonasm(r)
    assert out.action == "reject"
    assert "pleonasm" in out.reason.lower()


def test_filter_10_pleonasm_rejects_monter_en_haut():
    """'Monter en haut' : head='Monter' ∈ rule 2, tail 'en haut' → reject."""
    r = make_row("GRIMPER", "Monter en haut",
                 pos="verbe_infinitif")
    out = F.filter_10_pleonasm(r)
    assert out.action == "reject"


def test_filter_10_pleonasm_rejects_prevoir_a_l_avance():
    """'Prévoir à l'avance' : head='Prévoir', rule anticipation → reject."""
    r = make_row("ANTICIPER", "Prévoir à l'avance",
                 pos="verbe_infinitif")
    out = F.filter_10_pleonasm(r)
    assert out.action == "reject"


def test_filter_10_pleonasm_rejects_sortir_dehors():
    """'Sortir dehors' : head='Sortir', tail 'dehors' → reject."""
    r = make_row("PARTIR", "Sortir dehors",
                 pos="verbe_infinitif")
    out = F.filter_10_pleonasm(r)
    assert out.action == "reject"


def test_filter_10_pleonasm_rejects_descendre_en_bas():
    """'Descendre en bas' : head='Descendre', tail 'en bas' → reject."""
    r = make_row("CHUTER", "Descendre en bas",
                 pos="verbe_infinitif")
    out = F.filter_10_pleonasm(r)
    assert out.action == "reject"


def test_filter_10_pleonasm_rejects_repeter_a_nouveau():
    """'Répéter à nouveau' : head='Répéter', tail 'à nouveau' → reject."""
    r = make_row("REDIRE", "Répéter à nouveau",
                 pos="verbe_infinitif")
    out = F.filter_10_pleonasm(r)
    assert out.action == "reject"


def test_filter_10_pleonasm_accepts_legitimate_two_phrase_clue():
    """'Quitter en partant' hors du closed-set → accept (closed-set by design)."""
    r = make_row("DEPART", "Quitter en partant")
    out = F.filter_10_pleonasm(r)
    assert out.action == "accept", out.reason
