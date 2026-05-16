#!/usr/bin/env python3
"""Append grammalecte surfaces to words-fr.csv.

Two admission policies. Pick one per run; they're orthogonal.

(1) Length-band admission (`--mode length-band`, default).

The runtime corpus has historically capped at length 11; PRs #356/#357
push the daily-grid default to 15x12 and need supply at lengths 12-15.
This mode ports every grammalecte surface in the requested length range
whose `Total occurrences` clears `--min-freq`. Inputs are independent of
the current corpus — this is the "fresh import" mode.

(2) Lemma-anchored admission (`--mode lemma-anchored`).

Admits a surface form if BOTH:
- its lemma is already in the current words-fr.csv (i.e. the lemma has
  at least one row), AND
- its grammalecte tag set is not in the obscure-form blocklist
  (`ipsi` = passé simple, `simp` = subjonctif imparfait, and `cond`
  restricted to 1pl / 2pl).

This is the structural fix for the daily-grid cooldown convergence
problem: bench at 15x12 with accumulating cooldowns went from 8% to
100% success when the candidate pool widened to all surfaces. Lowering
the frequency floor alone gives ~3.7% pool growth at threshold=100;
the lemma-anchored rule adds ~57k surfaces because the limiting factor
is corpus *coverage* (inflected variants of common lemmas missing from
the wordlist), not the frequency threshold.

`--min-freq` defaults to 0 in this mode so the pipeline doesn't drop
rare inflections of common lemmas (`clignotais` freq=1 etc.). The
runtime CSV loader (CsvWordRepository) no longer applies a frequency
floor either; the blank-clue gate is the only remaining filter and
fires only when the inflater fails to produce a non-placeholder clue.

Both modes share the rest of the contract:

- The output keeps the input column order and appends new rows at the
  tail; reorder/sort is the caller's choice.
- Idempotent: re-running with the same flags is a no-op (existing words
  are not re-appended).
- Placeholder clues (`clue == word`) are written; the downstream
  generate-clues-lora-batched -> build-surface-clues -> merge-clues-into-wordlist
  flow upgrades placeholders to authored / inflected clues.
- The `difficulty` column is left empty for new rows (matching
  `add_short_word_clues.py`'s convention) -- the runtime generator does
  not read it.
- The `source` column is `grammalecte` and `source_license` is `MPL-2.0`.
"""
from __future__ import annotations
import argparse
import csv
import os
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
DEFAULT_LEXIQUE = Path(os.path.expanduser(
    "~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt"))


def _default_wordlist() -> Path:
    """Return whichever wordlist path exists on disk.

    The corpus moved from grid/api/.../resources to grid/infrastructure/...
    in PR #439. Support both for backwards compatibility; prefer the
    current location.
    """
    new_path = REPO / "grid/infrastructure/src/main/resources/words/words-fr.csv"
    old_path = REPO / "grid/api/src/main/resources/words/words-fr.csv"
    if new_path.exists():
        return new_path
    return old_path


DEFAULT_WORDLIST = _default_wordlist()


# Obscure-form blocklist. Surfaces tagged with any of these are
# crossword-noise (passé simple, subj imparfait) or tonal mismatches
# (the long conditional forms 1pl `-rions` / 2pl `-riez`). The cond
# singulars and 3pl are kept — `aimerait`, `viendrait`, `seraient`
# are fair-game clue surfaces.
def is_obscure_tag(tag_str: str) -> str | None:
    """Return a label if `tag_str` (space-separated grammalecte tags) is in
    the obscure-form blocklist. Otherwise None.
    """
    tags = set(tag_str.split())
    if "ipsi" in tags:
        return "passe-simple"
    if "simp" in tags:
        return "subj-imparfait"
    if "cond" in tags and ("1pl" in tags or "2pl" in tags):
        return "cond-1pl-2pl"
    return None


# Grid-cell foldability check, mirrors CsvWordRepository.foldToAscii.
# Kept as a local helper rather than importing the Kotlin rule because
# this script runs offline without a JVM; the rules are identical
# (NFD strip + œ/æ expansion + uppercase, must end up entirely A-Z).
_DIACRITICS = {
    "À": "A", "Á": "A", "Â": "A", "Ã": "A", "Ä": "A", "Å": "A",
    "Ç": "C", "È": "E", "É": "E", "Ê": "E", "Ë": "E",
    "Ì": "I", "Í": "I", "Î": "I", "Ï": "I",
    "Ò": "O", "Ó": "O", "Ô": "O", "Õ": "O", "Ö": "O",
    "Ù": "U", "Ú": "U", "Û": "U", "Ü": "U",
    "Ý": "Y", "Ÿ": "Y", "Ñ": "N",
    "à": "a", "á": "a", "â": "a", "ã": "a", "ä": "a", "å": "a",
    "ç": "c", "è": "e", "é": "e", "ê": "e", "ë": "e",
    "ì": "i", "í": "i", "î": "i", "ï": "i",
    "ò": "o", "ó": "o", "ô": "o", "õ": "o", "ö": "o",
    "ù": "u", "ú": "u", "û": "u", "ü": "u",
    "ý": "y", "ÿ": "y", "ñ": "n",
}


def _fold_to_ascii(text: str) -> str:
    import unicodedata
    nfd = unicodedata.normalize("NFD", text)
    stripped = "".join(c for c in nfd if not unicodedata.combining(c))
    return (
        stripped.replace("œ", "oe").replace("Œ", "OE")
        .replace("æ", "ae").replace("Æ", "AE")
        .upper()
    )


def _is_grid_placeable(text: str) -> bool:
    folded = _fold_to_ascii(text)
    return bool(folded) and all("A" <= c <= "Z" for c in folded)


def parse_grammalecte_length_band(
    lexique: Path,
    length_min: int,
    length_max: int,
    min_freq: int,
) -> dict[str, tuple[str, int]]:
    """Return surface -> (lemma, frequency) for length-band mode.

    When grammalecte tags a surface against multiple lemmas (homography),
    the pair with the highest `Total occurrences` wins. Ties keep
    whichever lemma was seen first; the file is freq-sorted so ties are
    rare and the choice is stable.
    """
    out: dict[str, tuple[str, int]] = {}
    seen_header = False
    flex_idx = lemme_idx = tot_idx = -1
    with lexique.open(encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            cols = line.rstrip("\n").split("\t")
            if not seen_header:
                if "Flexion" in cols and "Lemme" in cols and "Total occurrences" in cols:
                    flex_idx = cols.index("Flexion")
                    lemme_idx = cols.index("Lemme")
                    tot_idx = cols.index("Total occurrences")
                    seen_header = True
                continue
            if len(cols) <= max(flex_idx, lemme_idx, tot_idx):
                continue
            flex = cols[flex_idx]
            if not flex.isalpha():
                continue
            L = len(flex)
            if not (length_min <= L <= length_max):
                continue
            try:
                freq = int(cols[tot_idx])
            except ValueError:
                continue
            if freq < min_freq:
                continue
            lemma = cols[lemme_idx]
            existing = out.get(flex)
            if existing is None or freq > existing[1]:
                out[flex] = (lemma, freq)
    return out


def parse_grammalecte_lemma_anchored(
    lexique: Path,
    in_corpus_lemmas: set[str],
    length_min: int,
    length_max: int,
    min_freq: int,
) -> tuple[dict[str, tuple[str, int]], dict[str, int]]:
    """Return (surface -> (lemma, frequency), counters) for lemma-anchored mode.

    Admits a surface iff:
    - its lemma is in `in_corpus_lemmas`,
    - its grammalecte tag is not obscure (passé simple, subj imparfait,
      cond 1pl/2pl),
    - the surface text folds to all-A-Z (foldToAscii contract),
    - its length is in [length_min, length_max] and its frequency is
      >= min_freq.

    On homograph ties (same surface, two lemmas both in-corpus) the
    higher-freq row wins. Counters report admissions, blocks, and
    placement skips for the build summary.
    """
    out: dict[str, tuple[str, int]] = {}
    counters: dict[str, int] = {
        "scanned": 0,
        "lemma-not-in-corpus": 0,
        "obscure-passe-simple": 0,
        "obscure-subj-imparfait": 0,
        "obscure-cond-1pl-2pl": 0,
        "not-placeable": 0,
        "length-out-of-range": 0,
        "below-min-freq": 0,
        "admitted": 0,
    }
    seen_header = False
    flex_idx = lemme_idx = etiq_idx = tot_idx = -1
    with lexique.open(encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            cols = line.rstrip("\n").split("\t")
            if not seen_header:
                if ("Flexion" in cols and "Lemme" in cols
                        and "Étiquettes" in cols and "Total occurrences" in cols):
                    flex_idx = cols.index("Flexion")
                    lemme_idx = cols.index("Lemme")
                    etiq_idx = cols.index("Étiquettes")
                    tot_idx = cols.index("Total occurrences")
                    seen_header = True
                continue
            if len(cols) <= max(flex_idx, lemme_idx, etiq_idx, tot_idx):
                continue
            counters["scanned"] += 1
            lemma = cols[lemme_idx]
            if lemma.lower() not in in_corpus_lemmas:
                counters["lemma-not-in-corpus"] += 1
                continue
            obscure = is_obscure_tag(cols[etiq_idx])
            if obscure:
                counters[f"obscure-{obscure}"] += 1
                continue
            flex = cols[flex_idx]
            if not _is_grid_placeable(flex):
                counters["not-placeable"] += 1
                continue
            L = len(flex)
            if not (length_min <= L <= length_max):
                counters["length-out-of-range"] += 1
                continue
            try:
                freq = int(cols[tot_idx])
            except ValueError:
                continue
            if freq < min_freq:
                counters["below-min-freq"] += 1
                continue
            existing = out.get(flex)
            if existing is None or freq > existing[1]:
                out[flex] = (lemma, freq)
                if existing is None:
                    counters["admitted"] += 1
    return out, counters


def _load_in_corpus_lemmas(wordlist: Path) -> set[str]:
    """Return the set of lemmas present in the current wordlist (lowercased).

    Falls back to the `word` column when `lemma` is empty (legacy rows).
    """
    out: set[str] = set()
    with wordlist.open(encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            lemma = (r.get("lemma") or "").strip().lower()
            if not lemma:
                lemma = (r.get("word") or "").strip().lower()
            if lemma:
                out.add(lemma)
    return out


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--lexique", type=Path, default=DEFAULT_LEXIQUE)
    p.add_argument("--wordlist", type=Path, default=DEFAULT_WORDLIST)
    p.add_argument("--mode", choices=("length-band", "lemma-anchored"),
                   default="length-band",
                   help="Admission policy: length-band (default, legacy) or "
                        "lemma-anchored (new; admit surfaces of in-corpus "
                        "lemmas, blocklist passé simple / subj imparfait / "
                        "cond 1pl-2pl).")
    p.add_argument("--length-min", type=int, default=None,
                   help="Default 12 in length-band mode; 4 in lemma-anchored mode.")
    p.add_argument("--length-max", type=int, default=15)
    p.add_argument("--min-freq", type=int, default=None,
                   help="Default 1000 in length-band mode; 0 in lemma-anchored mode "
                        "(rare conjugations of common lemmas are fair game).")
    p.add_argument("--language", default="fr")
    p.add_argument("--source", default="grammalecte")
    p.add_argument("--source-license", default="MPL-2.0")
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args()

    # Mode-specific defaults.
    if args.length_min is None:
        args.length_min = 4 if args.mode == "lemma-anchored" else 12
    if args.min_freq is None:
        args.min_freq = 0 if args.mode == "lemma-anchored" else 1000

    if not args.lexique.exists():
        raise SystemExit(f"grammalecte lexique not found: {args.lexique}")
    if not args.wordlist.exists():
        raise SystemExit(f"wordlist not found: {args.wordlist}")

    # Load existing wordlist; preserve column order.
    with args.wordlist.open(encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        fieldnames = list(reader.fieldnames or [])
        rows = list(reader)
    required = {"word", "language", "length", "frequency", "difficulty",
                "clue", "source", "source_license", "lemma"}
    missing = required - set(fieldnames)
    if missing:
        raise SystemExit(f"wordlist missing columns: {missing}")

    existing_words = {r["word"].strip().lower() for r in rows}
    print(f"existing wordlist rows: {len(rows)}")
    print(f"existing distinct words: {len(existing_words)}")

    if args.mode == "lemma-anchored":
        in_corpus_lemmas = _load_in_corpus_lemmas(args.wordlist)
        print(f"in-corpus distinct lemmas: {len(in_corpus_lemmas)}")
        surfaces, counters = parse_grammalecte_lemma_anchored(
            args.lexique, in_corpus_lemmas,
            args.length_min, args.length_max, args.min_freq,
        )
        print(f"grammalecte lemma-anchored candidates "
              f"L{args.length_min}-L{args.length_max} freq>={args.min_freq}: "
              f"{len(surfaces)}")
        print("  counters:")
        for k, v in counters.items():
            print(f"    {k:>26s}: {v}")
    else:
        surfaces = parse_grammalecte_length_band(
            args.lexique, args.length_min, args.length_max, args.min_freq,
        )
        print(f"grammalecte length-band surfaces "
              f"L{args.length_min}-L{args.length_max} freq>={args.min_freq}: "
              f"{len(surfaces)}")

    # New rows = surfaces not already in the wordlist.
    new_rows: list[dict] = []
    for surface in sorted(surfaces):
        if surface.lower() in existing_words:
            continue
        lemma, freq = surfaces[surface]
        row = {col: "" for col in fieldnames}
        row["word"] = surface
        row["language"] = args.language
        row["length"] = str(len(surface))
        # Emit the actual surface frequency from grammalecte's "Total
        # occurrences" column. Do not boost or fabricate; the runtime
        # loader no longer applies a frequency floor (gatekeeping moved
        # here), so the field is informational rather than load-bearing.
        row["frequency"] = str(freq)
        # difficulty intentionally empty (see header docstring).
        # clue == word is the placeholder convention; the runtime loader
        # keeps any non-blank clue, and the build_surface_clues.py +
        # merge_clues_into_wordlist.py downstream steps can replace this
        # with an authored / inflected clue later.
        row["clue"] = surface
        row["source"] = args.source
        row["source_license"] = args.source_license
        row["lemma"] = lemma
        new_rows.append(row)

    print(f"new rows to append: {len(new_rows)}")
    if not new_rows:
        print("nothing to do")
        return
    if args.dry_run:
        print("--dry-run: not writing")
        return

    # Append in place. Keep column order; existing rows untouched.
    with args.wordlist.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, "") for k in fieldnames})
        for r in new_rows:
            w.writerow({k: r.get(k, "") for k in fieldnames})

    print(f"wrote {args.wordlist} (+{len(new_rows)} rows)")


if __name__ == "__main__":
    main()
