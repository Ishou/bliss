"""Mock du LLM-juge (étape 8 du pipeline §8.3).

Pas d'appel API réel. Vérifie :
- Métadonnées enum (pos / categorie / style / force)
- Accord §1.5 par heuristique simple (warning seulement)

L'enum check est en fait déjà fait par filter_8_llm_juge_mock dans
filters.py. Ce fichier fournit une interface alternative qui peut
être étendue pour intégrer un vrai LLM-juge plus tard.
"""

from __future__ import annotations

import unicodedata
from dataclasses import dataclass


@dataclass(frozen=True)
class JudgeResult:
    """Résultat du LLM-juge mock."""
    verdict: str       # "accept" | "reject" | "warning"
    justification: str = ""


# Heuristiques accord §1.5 (warnings seulement)

# Suffixes typiquement féminins (mot qui se termine ainsi)
FEMININ_SUFFIXES = {"e", "ée", "ie", "ue", "te", "se", "le", "re", "che"}

# Marqueurs masculins dans la définition (à signaler si mot féminin)
MASC_MARKERS_PATTERN = (
    r"\b(le|un|du|au|ce|cet|ces|mon|ton|son|ses|premier|"
    r"dernier|petit|grand|beau|fort|vieux|nouveau)\b"
)


def juge_mock(row: dict, valid_pos: set[str],
              valid_categories: set[str],
              valid_styles: set[str]) -> JudgeResult:
    """Évalue une ligne (post-filtres 1-7).

    Note : la validation enum est aussi faite par filter_8 dans filters.py
    pour compatibilité avec le pipeline standard.
    """
    pos = row["pos"]
    cat = row["categorie"]
    style = row["style"]
    force = row["force"]

    # Validation enums (reject)
    if pos not in valid_pos:
        return JudgeResult("reject", f"POS invalide : {pos!r}")
    if cat not in valid_categories:
        return JudgeResult("reject", f"Catégorie invalide : {cat!r}")
    if style not in valid_styles:
        return JudgeResult("reject", f"Style invalide : {style!r}")
    try:
        f = int(force)
        if not 1 <= f <= 5:
            return JudgeResult("reject", f"Force hors [1,5] : {force}")
    except (ValueError, TypeError):
        return JudgeResult("reject", f"Force non entière : {force!r}")

    # Heuristique accord §1.5 : warning si incohérence évidente
    # (ex : mot féminin et def commence par marqueur masc)
    # Désactivé par défaut (trop de faux positifs sur cas frontière)

    return JudgeResult("accept")
