#!/usr/bin/env python3
"""Append Greek letters and common 2/3-letter mots-fléchés fillers to
both `data/curated/short-fr.csv` (canonical) and `grid/api/.../words-fr.csv`
(runtime). Idempotent — existing rows are updated, new ones appended.

Picks were curated from common French mots-fléchés conventions:
- Greek letters (3-letter): TAU, PHI, PSI, RHO, KHI, ÊTA (the half not
  already in fr.csv as 2-letter MU/NU/XI/PI).
- Old-French / archaic mots-fléchés staples: ÂME, ÈRE, IRE, ODE, OST,
  HUE, OUF, OHÉ — these recur weekly in published grids.
- Geography / culture: TAO, ZEN, YAK, BEY, DEY, DOM, RIA, RIO.
- Verb past forms common as fillers: NIA, USA, MUÉ, FUT, EUT.
"""
from __future__ import annotations
import csv
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
CURATED = REPO / "data/curated/short-fr.csv"
RUNTIME = REPO / "grid/api/src/main/resources/words/words-fr.csv"

ENTRIES: list[tuple[str, str]] = [
    # 2-letter — Greek letters
    ("mu", "Lettre grecque"),
    ("nu", "Sans vêtement ou lettre grecque"),
    ("pi", "Constante mathématique grecque"),
    ("xi", "Lettre grecque"),
    # 2-letter — additions
    ("bu", "Participe de boire"),
    ("ci", "Adverbe de lieu proche"),
    ("dé", "Cube à six faces"),
    ("dû", "Ce que l'on doit"),
    ("eh", "Interjection d'appel"),
    ("ex", "Ancien partenaire"),
    ("fa", "Note de musique"),
    ("ha", "Cri d'étonnement"),
    ("là", "Adverbe de lieu"),
    ("né", "Venu au monde"),
    ("où", "Adverbe de lieu interrogatif"),
    ("pu", "Participe de pouvoir"),
    ("ra", "Dieu solaire égyptien"),
    ("té", "Règle d'équerre ou note"),
    ("ut", "Ancienne note de musique"),
    # 3-letter — Greek letters
    ("tau", "Lettre grecque (19e)"),
    ("phi", "Lettre grecque (21e)"),
    ("psi", "Lettre grecque (23e)"),
    ("rho", "Lettre grecque (17e)"),
    ("khi", "Lettre grecque (22e)"),
    ("êta", "Lettre grecque (7e)"),
    # 3-letter — common mots-fléchés staples
    ("âme", "Partie immatérielle de l'être"),
    ("ave", "Salutation latine"),
    ("bey", "Titre ottoman"),
    ("dey", "Souverain d'Alger"),
    ("dom", "Titre religieux"),
    ("duc", "Titre nobiliaire"),
    ("ego", "Le moi conscient"),
    ("ère", "Longue période historique"),
    ("eut", "Posséda jadis"),
    ("fut", "Tonneau ou verbe être"),
    ("gag", "Trait comique"),
    ("gin", "Alcool britannique"),
    ("gnu", "Bovidé africain"),
    ("hue", "Cri pour faire avancer"),
    ("ire", "Colère soutenue"),
    ("lia", "Attacha jadis"),
    ("mué", "Qui a changé"),
    ("nia", "Refusa jadis"),
    ("ode", "Poème lyrique"),
    ("ohé", "Interjection d'appel"),
    ("ost", "Armée féodale"),
    ("ouf", "Soupir de soulagement"),
    ("pur", "Sans mélange"),
    ("rab", "Supplément servi"),
    ("ria", "Estuaire breton"),
    ("rio", "Rivière espagnole"),
    ("ris", "Glande du veau"),
    ("rob", "Sirop concentré"),
    ("tao", "Doctrine chinoise"),
    ("toc", "Imitation grossière"),
    ("tuf", "Roche volcanique tendre"),
    ("yak", "Bovin tibétain"),
    ("zen", "Calme et serein"),
    ("zig", "Type d'individu"),
]


def main() -> None:
    pair_map = {w.lower(): c for w, c in ENTRIES}
    fields = ["word","language","length","frequency","difficulty","clue","source","source_license","lemma"]

    def upsert(path: Path) -> tuple[int, int]:
        with path.open(encoding="utf-8", newline="") as f:
            rows = list(csv.DictReader(f))
            file_fields = list(rows[0].keys()) if rows else fields
        by_word = {r["word"].lower(): r for r in rows}
        added = updated = 0
        for word, clue in pair_map.items():
            if word in by_word:
                r = by_word[word]
                r["clue"] = clue
                r["source"] = "bliss"
                r["source_license"] = "CC0-1.0"
                updated += 1
            else:
                new = {k: "" for k in file_fields}
                new["word"] = word
                new["language"] = "fr"
                new["length"] = str(len(word))
                new["frequency"] = "100000"
                new["clue"] = clue
                new["source"] = "bliss"
                new["source_license"] = "CC0-1.0"
                new["lemma"] = word
                rows.append(new)
                added += 1
        with path.open("w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=file_fields, lineterminator="\n")
            w.writeheader()
            for r in rows:
                w.writerow({k: r.get(k, "") for k in file_fields})
        return added, updated

    a1, u1 = upsert(CURATED)
    print(f"{CURATED}: added {a1}, updated {u1}")
    a2, u2 = upsert(RUNTIME)
    print(f"{RUNTIME}: added {a2}, updated {u2}")


if __name__ == "__main__":
    main()
