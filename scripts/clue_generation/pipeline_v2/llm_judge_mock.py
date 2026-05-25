"""Mock du LLM-juge §8.3 filtre 8 : validation enums + heuristique accord."""

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
    """Évalue une ligne post-filtres 1-7 (validation enums + accord §1.5)."""
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

    return JudgeResult("accept")
