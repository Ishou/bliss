from __future__ import annotations

import math
from pathlib import Path

import yaml

VALID_STYLES: frozenset[str] = frozenset({
    "definition_directe", "periphrase", "metonymie", "fonction_role",
    "calembour", "culturel", "cryptique", "cryptique_morphologique",
    "technique",
})

# Styles excluded from automatic generation (clue-style-guide-v2.md §4.5).
HORS_IA: frozenset[str] = frozenset({"calembour"})


def charger_distribution(path: Path) -> dict[str, float]:
    if not Path(path).exists():
        raise FileNotFoundError(f"Distribution introuvable : {path}")
    raw = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
    styles = raw.get("styles")
    if not isinstance(styles, dict) or not styles:
        raise ValueError(f"Clé `styles` manquante ou vide dans {path}")

    weights: dict[str, float] = {}
    for name, w in styles.items():
        if name not in VALID_STYLES:
            raise ValueError(f"unknown style: {name!r}")
        w = float(w)
        if w < 0:
            raise ValueError(f"negative weight for {name!r}: {w}")
        if name in HORS_IA and w > 0:
            raise ValueError(f"{name!r} is hors-IA and must have weight 0")
        if w > 0:
            weights[name] = w

    total = sum(weights.values())
    if abs(total - 1.0) > 1e-3:
        raise ValueError(f"weights must sum to 1.0 (got {total:.4f})")
    return dict(sorted(weights.items()))


def cibles_acceptation(weights: dict[str, float], n_target: int) -> dict[str, int]:
    styles = sorted(weights)
    raw = {s: weights[s] * n_target for s in styles}
    floors = {s: math.floor(raw[s]) for s in styles}
    deficit = n_target - sum(floors.values())
    # largest remainder first; tie-break by style name ascending
    order = sorted(styles, key=lambda s: (-(raw[s] - floors[s]), s))
    for s in order[:deficit]:
        floors[s] += 1
    return {s: floors[s] for s in styles}


def paires_pour_manque(
    cibles: dict[str, int],
    acceptes: dict[str, int],
    lemmes: list[str],
    seed: int,
    pass_idx: int,
    inflation: float,
) -> list[tuple[str, str]]:
    n = len(lemmes)
    pairs: list[tuple[str, str]] = []
    for s in sorted(cibles):
        manque = cibles[s] - acceptes.get(s, 0)
        if manque <= 0:
            continue
        req = math.ceil(manque * inflation)
        # deterministic per-(style,seed) base offset, advanced each pass
        base = (abs(hash((s, seed))) + pass_idx * (req + 1)) % n
        for i in range(req):
            pairs.append((lemmes[(base + i) % n], s))
    return pairs
