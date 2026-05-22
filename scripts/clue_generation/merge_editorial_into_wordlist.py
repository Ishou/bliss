#!/usr/bin/env python3
"""Merge editorial + cross-surface-derived clues into the runtime words-fr.csv."""
from __future__ import annotations

import argparse
import csv
import shutil
import sys
from pathlib import Path
from typing import Optional

REPO_ROOT = Path(__file__).resolve().parents[2]

DEFAULT_WORDLIST = (
    REPO_ROOT / "grid" / "infrastructure" / "src" / "main"
    / "resources" / "words" / "words-fr.csv"
)
DEFAULT_RAW_DIR = REPO_ROOT / "data" / "curated" / "raw"
DEFAULT_DERIVED_CSV = REPO_ROOT / "data" / "curated" / "derived" / "fr_inflations.csv"
DEFAULT_BACKUP = Path("/tmp/words-fr.before-editorial-merge.csv")


def load_lemmas(raw_dir: Path) -> dict[tuple[str, str], str]:
    """Read _lemmas.csv → {(Mot, Sens) → Lemme}."""
    out: dict[tuple[str, str], str] = {}
    path = raw_dir / "_lemmas.csv"
    if not path.exists():
        return out
    with path.open(encoding="utf-8") as fh:
        for r in csv.DictReader(fh, delimiter=";"):
            out[(r["Mot"], r["Sens"])] = r["Lemme"]
    return out


def load_editorial(
    raw_dir: Path, lemmas: dict[tuple[str, str], str],
) -> dict[str, dict[str, str]]:
    """Read every raw/fr_*.csv → {word_lower → {clue, lemma}}.

    Theme inferred from filename for traceability. Word case lowered for
    case-insensitive matching against the runtime CSV.
    """
    out: dict[str, dict[str, str]] = {}
    for csv_path in sorted(raw_dir.glob("fr_*.csv")):
        theme = _theme_from_filename(csv_path.name)
        with csv_path.open(encoding="utf-8") as fh:
            for r in csv.DictReader(fh, delimiter=";"):
                mot, def1 = r["Mot"], r["Définition 1"]
                if not def1:
                    continue  # skip empty Def 1 rows (shouldn't happen)
                word_lower = mot.lower()
                if word_lower in out:
                    continue  # first-row-wins for multi-sense words
                lemma = lemmas.get((mot, def1), mot.lower())
                out[word_lower] = {
                    "clue": def1,
                    "lemma": lemma.lower(),
                    "theme": theme,
                    "source_row": f"{mot} ({csv_path.name})",
                }
    return out


def load_derived(derived_csv: Path) -> dict[str, dict[str, str]]:
    """Read derived/fr_inflations.csv → {word_lower → {clue, lemma}}.

    Filters: only rows where Flag 1 is in {"ok", "verbatim"} are eligible
    (the others are inflater-defensive skips). First eligible row wins
    when multiple derivations exist for the same surface (e.g. AVAIT can
    come from AI source or EU source).
    """
    out: dict[str, dict[str, str]] = {}
    if not derived_csv.exists():
        return out
    with derived_csv.open(encoding="utf-8") as fh:
        for r in csv.DictReader(fh, delimiter=";"):
            if r.get("Flag 1") not in ("ok", "verbatim"):
                continue
            clue = r.get("Définition 1", "")
            if not clue:
                continue
            word_lower = r["Mot"].lower()
            if word_lower in out:
                continue
            out[word_lower] = {
                "clue": clue,
                "lemma": r.get("Lemme", "").lower() or word_lower,
                "source_row": f"{r['Mot source']} → {r['Mot']} (derived)",
            }
    return out


def _theme_from_filename(name: str) -> str:
    stem = Path(name).stem
    if not stem.startswith("fr_"):
        return ""
    rest = stem[3:]
    if rest.startswith("len") or rest.startswith("_"):
        return ""
    return rest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--wordlist", type=Path, default=DEFAULT_WORDLIST)
    parser.add_argument("--raw-dir", type=Path, default=DEFAULT_RAW_DIR)
    parser.add_argument("--derived", type=Path, default=DEFAULT_DERIVED_CSV)
    parser.add_argument("--backup", type=Path, default=DEFAULT_BACKUP)
    parser.add_argument("--dry-run", action="store_true",
                        help="print the change summary without writing the wordlist")
    args = parser.parse_args()

    lemmas = load_lemmas(args.raw_dir)
    editorial = load_editorial(args.raw_dir, lemmas)
    derived = load_derived(args.derived)
    print(f"Loaded {len(lemmas)} lemma overrides, "
          f"{len(editorial)} editorial words, "
          f"{len(derived)} derived words.", file=sys.stderr)

    with args.wordlist.open(encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))
        fieldnames = list(rows[0].keys()) if rows else []

    existing_words: set[str] = {r["word"].lower() for r in rows}

    updates_editorial = 0
    updates_derived = 0
    skipped_existing_clue = 0
    new_rows: list[dict[str, str]] = []

    for r in rows:
        word_lower = r["word"].lower()
        current_clue = (r.get("clue") or "").strip()

        if word_lower in editorial:
            new = editorial[word_lower]
            r["clue"] = new["clue"]
            r["lemma"] = new["lemma"]
            updates_editorial += 1
            continue

        if word_lower in derived:
            if current_clue:
                # Don't trample an existing LoRA-generated clue with a
                # derived one — preserve the human-validated work.
                skipped_existing_clue += 1
                continue
            new = derived[word_lower]
            r["clue"] = new["clue"]
            r["lemma"] = new["lemma"]
            updates_derived += 1

    # ADD new rows for editorial words not in the wordlist yet.
    for word_lower, payload in editorial.items():
        if word_lower in existing_words:
            continue
        new_rows.append(_build_new_row(word_lower, payload, fieldnames))
    print(f"Updates: {updates_editorial} editorial, "
          f"{updates_derived} derived. "
          f"Skipped (preserve existing clue): {skipped_existing_clue}. "
          f"New rows added: {len(new_rows)}.", file=sys.stderr)

    # Preserve original row order — the existing words-fr.csv is not in pure
    # (language, word) ASCII sort (bliss rows are pre-grouped at the top, then
    # grammalecte rows mostly alphabetical). Re-sorting would generate a 121k
    # line diff for no semantic reason. Update rows in-place and append new
    # ones at the end; a separate sorting pass can canonicalise later.
    rows.extend(new_rows)

    if args.dry_run:
        print("(dry-run; not writing)", file=sys.stderr)
        return 0

    shutil.copy2(args.wordlist, args.backup)
    print(f"Backup → {args.backup}", file=sys.stderr)
    # Force LF lineterminator to match the existing words-fr.csv. Without
    # this, csv.DictWriter emits CRLF and every line ends up "modified" in
    # the diff for no semantic reason.
    with args.wordlist.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    print(f"Wrote {len(rows)} rows → {args.wordlist}", file=sys.stderr)
    return 0


def _build_new_row(
    word_lower: str, payload: dict[str, str], fieldnames: list[str],
) -> dict[str, str]:
    row: dict[str, str] = {fn: "" for fn in fieldnames}
    row["word"] = word_lower
    row["language"] = "fr"
    row["length"] = str(len(word_lower))
    row["frequency"] = "100000"
    row["difficulty"] = ""
    row["clue"] = payload["clue"]
    row["source"] = "bliss"
    row["source_license"] = "CC0-1.0"
    row["lemma"] = payload["lemma"]
    return row


if __name__ == "__main__":
    sys.exit(main())
