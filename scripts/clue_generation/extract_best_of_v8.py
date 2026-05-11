#!/usr/bin/env python3
"""Extract iter17 v8 high-confidence shipped rows into a curatable CSV.

This is phase 1 of the self-distillation lane for iter18 SFT. It reads
`data/eval/production/lemma_clues_shipped.csv` and emits a filtered
subset to `data/lora_iter18_best_of_v8.csv` that:

  1. validation_flag == "ok"
  2. filter_score in [BEST_OF_MIN_SCORE, 0.9999)  -- score floor 0.80,
     and we explicitly drop perfect-1.0000 rows because those have
     historically included subtly-wrong cases (clair → "Évidemment"
     scored 1.0 in iter17 despite being wrong-sense)
  3. lemma not in iter18_authored_pairs.py  -- those lemmas have
     hand-authored chosens that are authoritative
  4. clue length <= MAX_CLUE_CHARS (25)  -- match the cell-fit budget
  5. capped at PER_LENGTH_CAP per lemma length so the lane enriches
     rather than dominates the curated SFT backbone

The output is the user's curation surface: open the CSV, scan for
wrong-sense or low-quality rows, delete the ones you don't want
trained on, save. `build_iter18_corpus.py` reads whatever survives.

Run after every full iter17-pipeline regen, or when shifting the
score floor.
"""
from __future__ import annotations
import csv, sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts" / "clue_generation"))
sys.path.insert(0, str(REPO / "scripts" / "eval"))
from clue_metrics import MAX_CLUE_CHARS  # noqa: E402

# lemma_clues_shipped.csv has an empty `pos` column — POS lives only in
# lemma_clues_raw_pos.csv (output of annotate_pos.py). raw_pos has all
# rows (shipped + dropped), so we filter at extraction time by the
# same gates the shipped split uses: validator_flag=='ok' AND
# filter_score >= ship threshold (here we raise to BEST_OF_MIN).
SOURCE          = REPO / "data" / "eval" / "production" / "lemma_clues_raw_pos.csv"
OUT_CSV         = REPO / "data" / "lora_iter18_best_of_v8.csv"
BEST_OF_MIN     = 0.80
PERFECT_SCORE   = 0.9999
PER_LENGTH_CAP  = 200


def _normalize_pos(label: str) -> str:
    label = (label or "").strip().lower()
    return {"adjectif": "adj", "adverbe": "adv"}.get(label, label)


def main() -> None:
    if not SOURCE.exists():
        sys.exit(f"missing {SOURCE} — run the production pipeline first")

    from iter18_authored_pairs import (
        CROSS_LINGUAL_HOMOGRAPH, POS_GLOSS_MISMATCH, NEAR_SYNONYM_CONFUSION,
    )
    excluded: set[str] = set()
    for bucket in (CROSS_LINGUAL_HOMOGRAPH, POS_GLOSS_MISMATCH, NEAR_SYNONYM_CONFUSION):
        for lemma, *_ in bucket:
            excluded.add(lemma)
    print(f"excluding {len(excluded)} authored lemmas: {sorted(excluded)}", file=sys.stderr)

    # Stage 1: filter by validator / score / length / authored-exclusion.
    by_len: dict[int, list[tuple[float, str, str, str]]] = {}
    skipped = {
        "validator_not_ok": 0, "authored_lemma": 0, "score_below_floor": 0,
        "score_perfect": 0, "over_cap_length": 0, "missing_pos": 0,
    }
    total_input = 0
    with SOURCE.open(encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            total_input += 1
            if r.get("validation_flag") != "ok":
                skipped["validator_not_ok"] += 1; continue
            lemma = r.get("lemma", "").strip().lower()
            if not lemma:
                continue
            if lemma in excluded:
                skipped["authored_lemma"] += 1; continue
            clue = r.get("lemma_clue", "").strip()
            if not clue:
                continue
            if len(clue) > MAX_CLUE_CHARS:
                skipped["over_cap_length"] += 1; continue
            try:
                score = float(r.get("filter_score", "0") or "0")
            except ValueError:
                continue
            if score < BEST_OF_MIN:
                skipped["score_below_floor"] += 1; continue
            if score >= PERFECT_SCORE:
                skipped["score_perfect"] += 1; continue
            pos = _normalize_pos(r.get("pos", ""))
            if not pos:
                skipped["missing_pos"] += 1; continue
            by_len.setdefault(len(lemma), []).append((score, lemma, pos, clue))

    # Stage 2: per-length cap (best score first).
    keep: list[tuple[str, str, str, float]] = []
    per_len_kept: dict[int, int] = {}
    for L in sorted(by_len):
        rows = sorted(by_len[L], reverse=True)[:PER_LENGTH_CAP]
        for score, lemma, pos, clue in rows:
            keep.append((lemma, pos, clue, score))
        per_len_kept[L] = len(rows)

    # Sort final output by (length, lemma) for easy scanning during curation.
    keep.sort(key=lambda r: (len(r[0]), r[0]))

    OUT_CSV.parent.mkdir(parents=True, exist_ok=True)
    with OUT_CSV.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["lemma", "pos", "clue", "filter_score"])
        for lemma, pos, clue, score in keep:
            w.writerow([lemma, pos, clue, f"{score:.4f}"])

    # Summary.
    print(f"\nread {total_input} rows from {SOURCE}", file=sys.stderr)
    print(f"skipped:", file=sys.stderr)
    for k, v in skipped.items():
        print(f"  {k}: {v}", file=sys.stderr)
    print(f"\nkept per length (cap={PER_LENGTH_CAP}):", file=sys.stderr)
    for L in sorted(per_len_kept):
        print(f"  L{L}: {per_len_kept[L]}", file=sys.stderr)
    print(f"\nwrote {OUT_CSV} ({len(keep)} rows)", file=sys.stderr)
    print(f"\nCurate by hand-deleting bad rows, then re-run build_iter18_corpus.py", file=sys.stderr)


if __name__ == "__main__":
    main()
