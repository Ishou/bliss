#!/usr/bin/env python3
"""Emit synonym-direct clue candidates from DBnary's synonym lists.

For each enriched row, walk the pipe-delimited `synonyms` column and pick
the first synonym that passes `validate_lemma_clue`. That filter requires:
- the head token is in grammalecte's lexique,
- the head is in citation form (lemma form),
- the head's POS matches the target's POS class.

Synonyms that don't pass go to a `rejected` log so the filter can be tuned.

Output schema matches lemma_clues.csv plus a `source` column ('dbnary-synonym').
Designed to be merged with the model-generated clues for downstream best-of
selection.

Usage:
    python scripts/eval/synonym_clues.py \\
        --input  data/eval/sample_iter6_enriched.csv \\
        --output data/eval/synonym_clues_iter6.csv \\
        --lexique ~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt
"""

from __future__ import annotations

import argparse
import csv
import os
import re
import sys
from pathlib import Path

from morphology_index import MorphologyIndex
from validate_clue import _FUNCTION_WORDS, _TOKEN_RE, validate_lemma_clue


# Synonym is rejected when any content-word token has total grammalecte
# occurrences below this threshold (or is unknown to the lexique entirely).
# Catches `Faire sisitte` (sisitte unknown), `Murge` (70 occ), `Venir à jubé`
# borderline (jubé ~100k, passes — wrong-sense rejection is a separate
# problem). 1000 is well below most real words, well above noise.
_MIN_TOKEN_FREQ = 1000

POS_NORMALIZE = {
    "verbe": "verbe", "nom": "nom",
    "adjectif": "adj", "adj": "adj",
    "adverbe": "adv", "adv": "adv",
    # DBnary uses English POS labels; keep them mapping too.
    "verb": "verbe", "noun": "nom", "adjective": "adj", "adverb": "adv",
}


def normalize_pos(label: str) -> str:
    return POS_NORMALIZE.get(label.strip().lower(), label.strip().lower())


def load_form_total_freq(path: Path) -> dict[str, int]:
    """Sum 'Total occurrences' across all lexique rows for each surface form.
    A surface like `branchie` accumulates the noun + (theoretical) verb-form
    occurrences into one number — fine for the 'is this word real?' check.
    """
    out: dict[str, int] = {}
    seen_header = False
    f_idx = t_idx = -1
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("#"):
                continue
            cols = line.rstrip("\n").split("\t")
            if not seen_header:
                if cols[:1] == ["id"] and "Flexion" in cols and "Total occurrences" in cols:
                    f_idx = cols.index("Flexion")
                    t_idx = cols.index("Total occurrences")
                    seen_header = True
                continue
            if len(cols) <= max(f_idx, t_idx):
                continue
            try:
                freq = int(cols[t_idx])
            except ValueError:
                continue
            surface = cols[f_idx].lower()
            out[surface] = out.get(surface, 0) + freq
    return out


def passes_token_freq(synonym: str, form_freq: dict[str, int]) -> tuple[bool, str]:
    """True iff every content-word token in the synonym has total occurrences
    ≥ _MIN_TOKEN_FREQ. Returns (ok, offending_token_or_empty)."""
    for tok in _TOKEN_RE.findall(synonym):
        tl = tok.lower()
        if tl in _FUNCTION_WORDS:
            continue
        freq = form_freq.get(tl, 0)
        if freq < _MIN_TOKEN_FREQ:
            return False, f"{tok!r}(freq={freq})"
    return True, ""


def pick_synonym(
    synonyms_csv: str,
    lemma: str,
    target_pos: str,
    index: MorphologyIndex,
    form_freq: dict[str, int],
) -> tuple[str, str, str]:
    """Return (chosen_synonym, validation_flag, rejection_log).

    Walks pipe-delimited synonyms in DBnary order and returns the first one
    that passes both the lexical validator AND the token-frequency filter
    (every content-word token has grammalecte freq ≥ _MIN_TOKEN_FREQ). The
    rejection log lists candidates that didn't pass and why."""
    rejections: list[str] = []
    for raw in synonyms_csv.split("|"):
        cand = raw.strip()
        if not cand:
            continue
        # Skip obvious junk: too long, too short.
        if len(cand) > 40 or len(cand) < 2:
            rejections.append(f"{cand!r}=length")
            continue
        # Capitalize for clue style.
        clue = cand[0].upper() + cand[1:]
        vr = validate_lemma_clue(clue, lemma, target_pos, index)
        if vr.flag != "ok":
            rejections.append(f"{cand!r}={vr.flag}")
            continue
        ok, offender = passes_token_freq(clue, form_freq)
        if not ok:
            rejections.append(f"{cand!r}=low-freq:{offender}")
            continue
        return clue, "ok", "; ".join(rejections)
    return "", "no-passing-synonym", "; ".join(rejections)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--lexique", type=Path, default=None)
    parser.add_argument("--rejections", type=Path, default=None,
                        help="optional log file for rejected synonyms (debug)")
    args = parser.parse_args()

    lex_path = args.lexique or Path(
        os.path.expanduser("~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt")
    )
    if not lex_path.exists():
        print(f"missing lexique: {lex_path}", file=sys.stderr)
        sys.exit(1)
    if not args.input.exists():
        print(f"missing {args.input}", file=sys.stderr)
        sys.exit(1)

    print(f"loading morphology index from {lex_path}...", file=sys.stderr)
    index = MorphologyIndex.load(lex_path)
    print(f"loading form frequencies from {lex_path}...", file=sys.stderr)
    form_freq = load_form_total_freq(lex_path)

    with args.input.open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))

    out_rows: list[dict[str, str]] = []
    rejection_lines: list[str] = []
    for r in rows:
        lemma = (r.get("lemma") or r["word"]).strip().lower()
        pos = normalize_pos(r.get("pos", ""))
        synonyms = r.get("synonyms", "")
        if not synonyms:
            continue
        clue, flag, log = pick_synonym(synonyms, lemma, pos, index, form_freq)
        if log:
            rejection_lines.append(f"{lemma}\t{log}")
        if flag != "ok":
            continue
        out_rows.append({
            "lemma": lemma,
            "pos": r.get("pos", ""),
            "definition": r.get("definition", ""),
            "synonyms": synonyms,
            "lemma_clue": clue,
            "attempts": "0",
            "validation_flag": "ok",
            "source": "dbnary-synonym",
            "rating": "",
        })

    fieldnames = [
        "lemma", "pos", "definition", "synonyms",
        "lemma_clue", "attempts", "validation_flag", "source", "rating",
    ]
    with args.output.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        w.writeheader()
        for row in out_rows:
            w.writerow(row)

    if args.rejections and rejection_lines:
        args.rejections.write_text("\n".join(rejection_lines) + "\n", encoding="utf-8")

    rows_with_syns = sum(1 for r in rows if r.get("synonyms"))
    print(f"Wrote {len(out_rows)} synonym-direct clues to {args.output} "
          f"({len(out_rows)}/{rows_with_syns} rows with synonyms passed validation, "
          f"{len(out_rows)}/{len(rows)} of all rows)")


if __name__ == "__main__":
    main()
