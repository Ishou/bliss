#!/usr/bin/env python3
"""Smoke test: load real grammalecte lexique, lemmatize every clue named in
`_lemmas.csv`, then preview the cross-surface fan-out for each editorial
verb-form entry.

Output is print-only; nothing is written to disk. Confirms that:
1. `lemmatize_clue` produces sensible lemma-form clues against real morphology.
2. `MorphologyIndex.by_lemma[lemma]` returns the right paradigm slice for
   each Lemme in `_lemmas.csv`.
3. The existing `inflect_clue.inflect_clue` forward-inflates the lemma-form
   clue into surface morphology without crashing.

Run from scripts/eval/:
    python3 smoke_propagate_editorial.py [path-to-lexique]
Default path: /Users/isho/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt
"""
from __future__ import annotations

import csv
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from inflect_clue import inflect_clue  # noqa: E402
from lemmatize_clue import lemmatize_clue  # noqa: E402
from morphology_index import MorphologyIndex  # noqa: E402

# Editorial corpus root (worktree-local).
REPO_ROOT = Path(__file__).resolve().parents[2]
LEMMAS_CSV = REPO_ROOT / "data" / "curated" / "raw" / "_lemmas.csv"
RAW_DIR = REPO_ROOT / "data" / "curated" / "raw"


def load_raw_clues() -> dict[tuple[str, str], dict[str, str]]:
    """Index every editorial row by (Mot, Def1). Skip _meta files."""
    out: dict[tuple[str, str], dict[str, str]] = {}
    for csv_path in RAW_DIR.glob("fr_*.csv"):
        with csv_path.open(encoding="utf-8") as fh:
            reader = csv.DictReader(fh, delimiter=";")
            for row in reader:
                key = (row["Mot"], row["Définition 1"])
                out[key] = {
                    "mot": row["Mot"],
                    "def1": row["Définition 1"],
                    "def2": row.get("Définition 2", ""),
                    "source_file": csv_path.name,
                }
    return out


def load_lemmas_table() -> list[dict[str, str]]:
    with LEMMAS_CSV.open(encoding="utf-8") as fh:
        reader = csv.DictReader(fh, delimiter=";")
        return list(reader)


def main() -> int:
    lex_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(
        "/Users/isho/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt"
    )
    if not lex_path.exists():
        print(f"ERROR: lexique not found at {lex_path}")
        return 1

    print(f"Loading lexique from {lex_path} …")
    idx = MorphologyIndex.load(lex_path)
    print(f"  loaded {len(idx.by_lemma)} lemmas, {len(idx.by_form)} surface forms.")

    raw_clues = load_raw_clues()
    lemmas = load_lemmas_table()
    print(f"  loaded {len(lemmas)} _lemmas rows, {len(raw_clues)} editorial rows.")

    total_derived = 0
    propagatable_clues = 0
    meta_clues = 0
    missing_in_raw = 0

    for entry in lemmas:
        mot, sens, lemme, morph = entry["Mot"], entry["Sens"], entry["Lemme"], entry["Morphologie"]
        key = (mot, sens)
        raw = raw_clues.get(key)
        print(f"\n=== {mot}  →  lemma={lemme}  ({morph}) ===")
        if raw is None:
            print(f"  [missing] no row matched (Mot={mot!r}, Def1={sens!r})")
            missing_in_raw += 1
            continue
        print(f"  source: {raw['source_file']}")
        clues = [raw["def1"]]
        if raw["def2"]:
            clues.append(raw["def2"])

        # Lemmatize each clue.
        lemma_forms: list[tuple[str, str]] = []  # (original, lemma-form, flag)
        for clue in clues:
            r = lemmatize_clue(clue, idx)
            lemma_forms.append((clue, r.text, r.flag))
            tag = f"[{r.flag}] " if r.flag else ""
            print(f"  clue: {clue!r:50} → {tag}{r.text!r}")

        # Enumerate other surfaces of the lemma in length 4-11, alphabetic.
        surfaces = idx.by_lemma.get(lemme.lower(), [])
        eligible: list[tuple[str, frozenset[str]]] = []
        seen_surfaces: set[str] = set()
        for surf, tags in surfaces:
            if surf == mot.lower():
                continue
            if not surf.isalpha():
                continue
            if not (4 <= len(surf) <= 11):
                continue
            if surf in seen_surfaces:
                continue
            seen_surfaces.add(surf)
            eligible.append((surf, tags))

        if not eligible:
            print(f"  no eligible surfaces (length 4-11) for {lemme}")
            continue

        # Preview first 5 derived rows per clue.
        print(f"  {len(eligible)} eligible surfaces; previewing 5:")
        for surf, tags in eligible[:5]:
            print(f"    surface {surf.upper()} ({_short_tags(tags)})")
            for original, lemma_form, flag in lemma_forms:
                if flag in ("meta-style", "no-content-verb", "empty"):
                    inflated_text = lemma_form
                    inflated_flag = "verbatim"
                else:
                    r = inflect_clue(lemma_form, tags, idx)
                    inflated_text = r.text
                    inflated_flag = r.flag or "ok"
                print(f"      [{inflated_flag}] {original!r:40} → {inflated_text!r}")
            total_derived += len(lemma_forms)

        # Count totals.
        for _, lemma_form, flag in lemma_forms:
            if flag in ("meta-style", "no-content-verb", "empty"):
                meta_clues += 1
            else:
                propagatable_clues += 1

    print("\n--- summary ---")
    print(f"  _lemmas rows:                 {len(lemmas)}")
    print(f"  missing in raw CSVs:          {missing_in_raw}")
    print(f"  propagatable clue Defs:       {propagatable_clues}")
    print(f"  meta-style / unchanged Defs:  {meta_clues}")
    print(f"  derived rows previewed:       {total_derived} (capped at 5 per entry)")
    return 0


def _short_tags(tags: frozenset[str]) -> str:
    relevant = {"ipre", "iimp", "ipsi", "ifut", "cond", "spre", "simp", "impe",
                "ppre", "ppas", "infi", "1sg", "2sg", "3sg", "1pl", "2pl", "3pl",
                "mas", "fem", "epi", "sg", "pl", "inv"}
    return ",".join(sorted(t for t in tags if t in relevant))


if __name__ == "__main__":
    sys.exit(main())
