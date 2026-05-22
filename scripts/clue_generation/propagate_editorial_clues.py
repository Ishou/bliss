#!/usr/bin/env python3
"""Cross-surface propagation step: lemmatize editorial clues and fan-out to all paradigm surfaces."""
from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "scripts" / "eval"))

from inflect_clue import inflect_clue  # noqa: E402
from lemmatize_clue import lemmatize_clue  # noqa: E402
from morphology_index import MorphologyIndex  # noqa: E402

DEFAULT_LEXIQUE = None
DEFAULT_OUT = REPO_ROOT / "data" / "curated" / "derived" / "fr_inflations.csv"
RAW_DIR = REPO_ROOT / "data" / "curated" / "raw"
LEMMAS_CSV = RAW_DIR / "_lemmas.csv"

MIN_LEN = 3
MAX_LEN = 11


def load_raw_clues() -> dict[tuple[str, str], dict[str, str]]:
    """Index every editorial row by (Mot, Def1). Skip metadata files (`_*.csv`)."""
    out: dict[tuple[str, str], dict[str, str]] = {}
    for csv_path in sorted(RAW_DIR.glob("fr_*.csv")):
        with csv_path.open(encoding="utf-8") as fh:
            reader = csv.DictReader(fh, delimiter=";")
            for row in reader:
                key = (row["Mot"], row["Définition 1"])
                out[key] = {
                    "mot": row["Mot"],
                    "def1": row["Définition 1"],
                    "def2": row.get("Définition 2", ""),
                    "theme": _theme_from_filename(csv_path.name),
                }
    return out


def _theme_from_filename(name: str) -> str:
    """Extract theme from `fr_<theme>.csv`. Untagged length files → empty."""
    stem = Path(name).stem  # fr_units → "fr_units"
    if not stem.startswith("fr_"):
        return ""
    rest = stem[3:]
    if rest.startswith("len"):
        return ""  # fr_len02 / fr_len03 are untagged
    return rest


def load_lemmas_table() -> list[dict[str, str]]:
    with LEMMAS_CSV.open(encoding="utf-8") as fh:
        reader = csv.DictReader(fh, delimiter=";")
        return list(reader)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--lexique", type=Path, default=DEFAULT_LEXIQUE)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--min-len", type=int, default=MIN_LEN)
    parser.add_argument("--max-len", type=int, default=MAX_LEN)
    args = parser.parse_args()

    if args.lexique is None or not args.lexique.exists():
        print("ERROR: pass --lexique /path/to/lexique-grammalecte-fr-v7.7.txt")
        return 1

    print(f"Loading lexique from {args.lexique} …", file=sys.stderr)
    idx = MorphologyIndex.load(args.lexique)
    print(f"  loaded {len(idx.by_lemma)} lemmas, "
          f"{len(idx.by_form)} surface forms.", file=sys.stderr)

    raw_clues = load_raw_clues()
    lemmas = load_lemmas_table()
    print(f"  loaded {len(lemmas)} _lemmas rows, "
          f"{len(raw_clues)} editorial rows.", file=sys.stderr)

    derived_rows: list[dict[str, str]] = []
    skipped_no_raw = 0

    for entry in lemmas:
        mot, sens, lemme = entry["Mot"], entry["Sens"], entry["Lemme"]
        raw = raw_clues.get((mot, sens))
        if raw is None:
            skipped_no_raw += 1
            continue

        clue1 = raw["def1"]
        clue2 = raw["def2"]
        theme = raw["theme"]

        lem1 = lemmatize_clue(clue1, idx)
        lem2 = lemmatize_clue(clue2, idx) if clue2 else None

        for surface, tags in _eligible_surfaces(idx, lemme, mot,
                                                args.min_len, args.max_len):
            inflated1_text, flag1 = _propagate(lem1.text, lem1.flag, tags, idx)
            if lem2 is None:
                inflated2_text, flag2 = "", ""
            else:
                inflated2_text, flag2 = _propagate(
                    lem2.text, lem2.flag, tags, idx
                )

            derived_rows.append({
                "Mot": surface.upper(),
                "Définition 1": inflated1_text,
                "Définition 2": inflated2_text,
                "Lemme": lemme,
                "Mot source": mot,
                "Thème source": theme,
                "Flag 1": flag1,
                "Flag 2": flag2,
            })

    args.out.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "Mot", "Définition 1", "Définition 2",
        "Lemme", "Mot source", "Thème source",
        "Flag 1", "Flag 2",
    ]
    with args.out.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fieldnames, delimiter=";")
        writer.writeheader()
        for row in derived_rows:
            writer.writerow(row)

    print(f"\n--- summary ---", file=sys.stderr)
    print(f"  _lemmas rows:           {len(lemmas)}", file=sys.stderr)
    print(f"  missing in raw CSVs:    {skipped_no_raw}", file=sys.stderr)
    print(f"  derived rows written:   {len(derived_rows)} → {args.out}",
          file=sys.stderr)
    return 0


def _eligible_surfaces(
    idx: MorphologyIndex, lemme: str, source_mot: str,
    min_len: int, max_len: int,
) -> list[tuple[str, frozenset[str]]]:
    """Return unique surfaces of `lemme` in [min_len, max_len], alphabetic,
    excluding the source mot. Ordered as grammalecte returns them (stable)."""
    surfaces = idx.by_lemma.get(lemme.lower(), [])
    seen: set[str] = set()
    out: list[tuple[str, frozenset[str]]] = []
    src_lo = source_mot.lower()
    for surf, tags in surfaces:
        if surf == src_lo:
            continue
        if not surf.isalpha():
            continue
        if not (min_len <= len(surf) <= max_len):
            continue
        if surf in seen:
            continue
        seen.add(surf)
        out.append((surf, tags))
    return out


def _propagate(
    lemma_form: str, lemma_flag: str,
    surface_tags: frozenset[str], idx: MorphologyIndex,
) -> tuple[str, str]:
    """Forward-inflate a lemma-form clue to a surface's morphology.

    Returns (text, flag). Meta-style and similarly-non-inflectable inputs
    pass through verbatim with `flag='verbatim'`. Otherwise the existing
    `inflect_clue` is delegated to; its flag is reported as-is."""
    if lemma_flag in ("meta-style", "no-content-verb", "empty"):
        return lemma_form, "verbatim"
    result = inflect_clue(lemma_form, surface_tags, idx)
    return result.text, result.flag or "ok"


if __name__ == "__main__":
    sys.exit(main())
