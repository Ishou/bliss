"""Normalisations §8.4 du pipeline (8 fonctions auto-correctibles)."""

from __future__ import annotations

import re
import unicodedata

APO = "’"

NormResult = tuple[str, bool]


def norm_1_apostrophe(defi: str) -> NormResult:
    """1. Apostrophe droite U+0027 → typographique U+2019."""
    new = defi.replace("'", APO)
    return new, new != defi


def norm_2_espaces_multiples(defi: str) -> NormResult:
    """2. Séquences d'espaces blancs → un seul espace."""
    new = re.sub(r"[ \t]{2,}", " ", defi)
    return new, new != defi


def norm_3_trim(defi: str) -> NormResult:
    """3. Trim début et fin."""
    new = defi.strip()
    return new, new != defi


def norm_4_initiale_majuscule(defi: str) -> NormResult:
    """4. Initiale en majuscule (sauf si déjà majuscule)."""
    if not defi:
        return defi, False
    if defi[0].isalpha() and not defi[0].isupper():
        new = defi[0].upper() + defi[1:]
        return new, True
    return defi, False


def norm_5_point_final(defi: str) -> NormResult:
    """5. Supprime le point final sauf si l'avant-dernier char n'est pas une lettre."""
    if not defi:
        return defi, False
    if defi.endswith("."):
        if len(defi) > 1 and defi[-2].isalpha():
            new = defi[:-1]
            return new, True
    return defi, False


def norm_6_guillemets_enveloppants(defi: str) -> NormResult:
    """6. Suppression des guillemets enveloppants « ... » ou \"...\"."""
    if not defi:
        return defi, False
    # Guillemets français : « X »
    m = re.match(r"^«\s*(.+?)\s*»$", defi)
    if m:
        return m.group(1), True
    # Guillemets droits : "X"
    m = re.match(r'^"(.+?)"$', defi)
    if m:
        return m.group(1), True
    return defi, False


def norm_7_nfc(defi: str) -> NormResult:
    """7. Normalisation Unicode NFC."""
    new = unicodedata.normalize("NFC", defi)
    return new, new != defi


def norm_8_tabs_newlines(defi: str) -> NormResult:
    """8. Suppression des tabulations et retours de ligne internes."""
    new = re.sub(r"[\t\n\r]+", " ", defi)
    return new, new != defi


# Ordre canonique des normalisations
NORMALIZATIONS = [
    ("norm_1_apostrophe", norm_1_apostrophe),
    ("norm_2_espaces_multiples", norm_2_espaces_multiples),
    ("norm_3_trim", norm_3_trim),
    ("norm_4_initiale_majuscule", norm_4_initiale_majuscule),
    ("norm_5_point_final", norm_5_point_final),
    ("norm_6_guillemets_enveloppants", norm_6_guillemets_enveloppants),
    ("norm_7_nfc", norm_7_nfc),
    ("norm_8_tabs_newlines", norm_8_tabs_newlines),
]


def normaliser_tout(defi: str) -> tuple[str, list[str]]:
    """Applique les 8 normalisations en cascade, retourne (def_finale, noms_appliqués)."""
    applied: list[str] = []
    for name, fn in NORMALIZATIONS:
        defi, was_applied = fn(defi)
        if was_applied:
            applied.append(name)
    return defi, applied
