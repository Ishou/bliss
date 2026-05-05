#!/usr/bin/env bash
# Run the full clue-gen pipeline on top-X lemmas per length (4-11).
# X chosen for ~6h max wall-clock at ~0.4 clue/s through generate.
#
# Lengths are round-robin interleaved at the input level so a kill during
# generation leaves every length partially populated rather than only the
# shortest ones. generate_clues_lemma writes incrementally (every 20 ok
# clues + at end of each round) so mid-run kills preserve work.
#
# Output:
#   data/eval/top_${X}/sample.csv               - the interleaved sample
#   data/eval/top_${X}/sample_with_dbnary.csv   - DBnary fetch
#   data/eval/top_${X}/sample_enriched.csv      - morphology + multi-sense defs
#   data/eval/top_${X}/lemma_clues.csv          - generated clues (resumable)
set -euo pipefail

X="${X:-1000}"
LEXIQUE="${LEXIQUE:-$HOME/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt}"
MODEL="${MODEL:-command-r}"
CONCURRENCY="${CONCURRENCY:-16}"

REPO_ROOT="$(cd "$(dirname "$0")"/../.. && pwd)"
cd "$REPO_ROOT"

OUT_DIR="data/eval/top_${X}"
mkdir -p "$OUT_DIR"

SAMPLE="$OUT_DIR/sample.csv"
WITH_DEFS="$OUT_DIR/sample_with_dbnary.csv"
ENRICHED="$OUT_DIR/sample_enriched.csv"
CLUES="$OUT_DIR/lemma_clues.csv"

PY=.venv/bin/python

if [[ ! -f "$SAMPLE" ]]; then
  echo "[1/4] sampling top-$X per length (4-11) and round-robin interleaving"
  RAW="$OUT_DIR/sample_raw.csv"
  $PY scripts/eval/sample_lemmas.py \
    --top-per-length "$X" --min-length 4 --max-length 11 \
    --output "$RAW" 2>&1 | tail -3 || true
  $PY - <<PYEOF
# Round-robin interleave by length so kills during generation cover all lengths.
import csv
from collections import defaultdict
from pathlib import Path
src = Path("$RAW"); dst = Path("$SAMPLE")
with src.open(encoding="utf-8", newline="") as f:
    rows = list(csv.DictReader(f))
by_len = defaultdict(list)
for r in rows:
    by_len[int(r["length"])].append(r)
out = []
max_len = max(len(b) for b in by_len.values())
for i in range(max_len):
    for L in sorted(by_len):
        bucket = by_len[L]
        if i < len(bucket):
            out.append(bucket[i])
with dst.open("w", encoding="utf-8", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(rows[0].keys()), lineterminator="\n")
    w.writeheader()
    for r in out: w.writerow(r)
print(f"interleaved {len(out)} rows -> {dst}")
PYEOF
else
  echo "[1/4] reusing existing sample $SAMPLE"
fi

if [[ "${SKIP_DBNARY:-0}" == "1" ]]; then
  echo "[2/4] SKIP_DBNARY=1: copying sample as with-defs (empty pos/def/syn)"
  cp "$SAMPLE" "$WITH_DEFS"
else
  echo "[2/4] DBnary fetch (cached, parallelized)"
  $PY scripts/eval/fetch_dbnary_for_sample.py \
    --input "$SAMPLE" --output "$WITH_DEFS" --workers 16 2>&1 | tail -3
fi

echo "[3/4] enrich with morphology + multi-sense defs"
ENRICH_FLAGS=()
[[ "${SKIP_DBNARY:-0}" == "1" ]] && ENRICH_FLAGS+=(--no-dbnary)
$PY scripts/eval/enrich_with_morphology.py \
  --lexique "$LEXIQUE" --input "$WITH_DEFS" --output "$ENRICHED" "${ENRICH_FLAGS[@]}" 2>&1 | tail -3

echo "[4/4] generating clues (incremental persist; resume-safe)"
exec $PY -u scripts/eval/generate_clues_lemma.py \
  --model "$MODEL" --concurrency "$CONCURRENCY" \
  --prompt scripts/eval/prompts/clue_lemma_v1.txt \
  --input "$ENRICHED" --output "$CLUES"
