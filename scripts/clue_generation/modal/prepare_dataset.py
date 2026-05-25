#!/usr/bin/env python3
"""Stratified train/val split of gold_pilot_v1.csv → data/seed/ JSONL."""

from __future__ import annotations

import csv
import json
import random
from collections import Counter, defaultdict
from pathlib import Path

# parents[3] = bliss/ root (modal → clue_generation → scripts → bliss)
ROOT = Path(__file__).resolve().parents[3]
INPUT_CSV = ROOT / "data" / "curated" / "gold_pilot_v1.csv"
TRAIN_OUT = ROOT / "data" / "seed" / "gold_pilot_v1_train.jsonl"
VAL_OUT = ROOT / "data" / "seed" / "gold_pilot_v1_val.jsonl"

SEED = 42
VAL_RATIO = 0.12  # cible 12 % en val (≈ 14 lignes sur 114)

# Template prompt utilisateur (v1 — minimaliste, sans métadonnées)
USER_PROMPT_TEMPLATE = "Donne une définition de mot fléché pour {mot}."


def charger_csv(path: Path) -> list[dict]:
    """Charge un CSV ; et retourne une liste de dicts."""
    with path.open(encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f, delimiter=";")
        return list(reader)


def split_stratifie(rows: list[dict]) -> tuple[list[dict], list[dict]]:
    """Stratified train/val split by force, seed=42."""
    rng = random.Random(SEED)
    par_force: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        par_force[row["force"]].append(row)

    train: list[dict] = []
    val: list[dict] = []
    # Tri des forces pour reproductibilité (ordre déterministe)
    for force in sorted(par_force.keys()):
        groupe = list(par_force[force])
        rng.shuffle(groupe)
        n_val = round(len(groupe) * VAL_RATIO)
        val.extend(groupe[:n_val])
        train.extend(groupe[n_val:])

    # Mélange final pour éviter que toutes les F1 soient au début, etc.
    rng.shuffle(train)
    rng.shuffle(val)
    return train, val


def construire_chat_entry(row: dict) -> dict:
    """Construit une entrée JSONL au format chat Mistral / SFTTrainer."""
    return {
        "messages": [
            {
                "role": "user",
                "content": USER_PROMPT_TEMPLATE.format(mot=row["mot"]),
            },
            {
                "role": "assistant",
                "content": row["definition"],
            },
        ]
    }


def ecrire_jsonl(entries: list[dict], path: Path) -> None:
    """Écrit une liste d'entrées en JSONL (une par ligne, UTF-8)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for entry in entries:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def afficher_recap(train_rows: list[dict], val_rows: list[dict],
                   train_entries: list[dict]) -> None:
    """Affiche un récap des distributions et 2 exemples JSONL."""
    total = len(train_rows) + len(val_rows)
    print()
    print(f">> Split : {len(train_rows)} train + {len(val_rows)} val "
          f"= {total} total "
          f"({len(val_rows) / total * 100:.1f} % en val)")

    # Distribution par force
    print()
    print(">> Distribution par force :")
    print(f"   {'force':<6s}  {'train':>5s}  {'val':>3s}")
    train_f = Counter(r["force"] for r in train_rows)
    val_f = Counter(r["force"] for r in val_rows)
    for force in sorted(set(train_f) | set(val_f)):
        print(f"   F{force:<5s}  {train_f.get(force, 0):5d}  "
              f"{val_f.get(force, 0):3d}")

    # Distribution par POS
    print()
    print(">> Distribution par POS :")
    print(f"   {'pos':<22s}  {'train':>5s}  {'val':>3s}")
    train_p = Counter(r["pos"] for r in train_rows)
    val_p = Counter(r["pos"] for r in val_rows)
    for pos in sorted(set(train_p) | set(val_p)):
        print(f"   {pos:<22s}  {train_p.get(pos, 0):5d}  "
              f"{val_p.get(pos, 0):3d}")

    # Exemples
    print()
    print(">> Exemples JSONL (2 premières lignes du train) :")
    for entry in train_entries[:2]:
        print("   " + json.dumps(entry, ensure_ascii=False))


def main() -> None:
    if not INPUT_CSV.exists():
        raise SystemExit(f"ERREUR : {INPUT_CSV} introuvable")

    rows = charger_csv(INPUT_CSV)
    print(f"Chargé : {len(rows)} entrées depuis {INPUT_CSV.name}")

    train_rows, val_rows = split_stratifie(rows)

    train_entries = [construire_chat_entry(r) for r in train_rows]
    val_entries = [construire_chat_entry(r) for r in val_rows]

    ecrire_jsonl(train_entries, TRAIN_OUT)
    ecrire_jsonl(val_entries, VAL_OUT)

    print(f"Écrit : {TRAIN_OUT.relative_to(ROOT)} "
          f"({len(train_entries)} lignes)")
    print(f"Écrit : {VAL_OUT.relative_to(ROOT)} "
          f"({len(val_entries)} lignes)")

    afficher_recap(train_rows, val_rows, train_entries)


if __name__ == "__main__":
    main()
