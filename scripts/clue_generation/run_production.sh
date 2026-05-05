#!/usr/bin/env bash
# Production pipeline: top-X-per-length lemmas → iter10 generate → filter v5 score
# → split shipped/dropped at threshold.
#
# Resume-safe: generate_clues_lora.py persists incrementally so interrupting
# is fine — re-run picks up where it stopped.
#
# Output:
#   data/eval/production/lemma_clues_raw.csv      - all generated (with filter score)
#   data/eval/production/lemma_clues_shipped.csv  - score >= threshold
#   data/eval/production/lemma_clues_dropped.csv  - score < threshold
set -euo pipefail

X="${X:-5000}"
THRESHOLD="${THRESHOLD:-0.65}"
GEN_MODEL="${GEN_MODEL:-mlx-community/c4ai-command-r-08-2024-4bit}"
GEN_ADAPTER="${GEN_ADAPTER:-models/lora-clue-v3}"
FILTER="${FILTER:-models/filter-camembert-v5}"

REPO="$(cd "$(dirname "$0")"/../.. && pwd)"
cd "$REPO"

OUT_DIR="data/eval/production"
mkdir -p "$OUT_DIR"

SAMPLE_JSONL="$OUT_DIR/sample.jsonl"
RAW_CSV="$OUT_DIR/lemma_clues_raw.csv"
SHIPPED="$OUT_DIR/lemma_clues_shipped.csv"
DROPPED="$OUT_DIR/lemma_clues_dropped.csv"

PY=.venv/bin/python

# === Phase 1: sample top-X per length, length-interleaved ===
if [[ ! -f "$SAMPLE_JSONL" ]]; then
  echo "[1/3] sampling top-$X per length (4-11), length-interleaved"
  $PY <<PYEOF
import csv, json, random
from pathlib import Path
from collections import defaultdict

ws = Path("grid/api/src/main/resources/words/words-fr.csv")
candidates = defaultdict(list)
with ws.open() as f:
    for r in csv.DictReader(f):
        lemma = (r.get("lemma") or r.get("word","")).strip().lower()
        word = r.get("word","").strip().lower()
        if not lemma or lemma != word or not lemma.isalpha(): continue
        L = len(lemma)
        if not (4 <= L <= 11): continue
        try: freq = float(r.get("frequency") or 0)
        except: freq = 0
        candidates[L].append((lemma, freq))

# Top-$X per length, sorted by freq descending
chosen_per_len = {}
for L in range(4, 12):
    chosen_per_len[L] = sorted(candidates.get(L, []), key=lambda x: -x[1])[:$X]
    print(f"  L{L}: {len(chosen_per_len[L])}")

# Round-robin interleave: 1 length-4, 1 length-5, ..., 1 length-11, repeat
out = []
max_len = max(len(b) for b in chosen_per_len.values())
for i in range(max_len):
    for L in sorted(chosen_per_len):
        bucket = chosen_per_len[L]
        if i < len(bucket):
            out.append(bucket[i][0])
print(f"total: {len(out)}")

dst = Path("$SAMPLE_JSONL")
with dst.open("w", encoding="utf-8") as f:
    for lemma in out:
        prompt = f"Génère une définition mots-fléchés courte pour: {lemma.upper()}"
        f.write(json.dumps({"messages":[{"role":"user","content":prompt},{"role":"assistant","content":""}]}, ensure_ascii=False)+"\n")
print(f"wrote {dst}")
PYEOF
else
  echo "[1/3] reusing $SAMPLE_JSONL"
fi

# === Phase 2: generate clues with iter10 (resume-safe, batched) ===
echo "[2/3] generating clues with iter10 (batched, resume-safe; incremental persist)"
PYTHONUNBUFFERED=1 $PY -u scripts/clue_generation/generate_clues_lora_batched.py \
  --model "$GEN_MODEL" \
  --adapter "$GEN_ADAPTER" \
  --test "$SAMPLE_JSONL" \
  --output "$RAW_CSV" \
  --batch-size 16

# === Phase 3: score with filter, split shipped/dropped ===
echo "[3/3] scoring with filter $FILTER and splitting at T=$THRESHOLD"
$PY <<PYEOF
import csv
from pathlib import Path
from sentence_transformers import SentenceTransformer
from sentence_transformers.util import cos_sim

raw_path = Path("$RAW_CSV")
T = $THRESHOLD

with raw_path.open() as f:
    rows = list(csv.DictReader(f))
print(f"loaded {len(rows)} rows")

model = SentenceTransformer("$FILTER")
le = model.encode([r["lemma"] for r in rows], convert_to_tensor=True,
                  show_progress_bar=True, batch_size=64)
ce = model.encode([r["lemma_clue"] for r in rows], convert_to_tensor=True,
                  show_progress_bar=True, batch_size=64)
for r, a, b in zip(rows, le, ce):
    r["filter_score"] = f"{float(cos_sim(a.unsqueeze(0), b.unsqueeze(0)).item()):.4f}"

# Save raw with scores
fieldnames = list(rows[0].keys())
if "filter_score" not in fieldnames: fieldnames.append("filter_score")
with raw_path.open("w", encoding="utf-8", newline="") as f:
    w = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
    w.writeheader()
    for r in rows: w.writerow({k: r.get(k, "") for k in fieldnames})

shipped = [r for r in rows if float(r["filter_score"]) >= T and r["validation_flag"] == "ok"]
dropped = [r for r in rows if not (float(r["filter_score"]) >= T and r["validation_flag"] == "ok")]

for path, data in [("$SHIPPED", shipped), ("$DROPPED", dropped)]:
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        w.writeheader()
        for r in data: w.writerow({k: r.get(k, "") for k in fieldnames})
    print(f"wrote {path}: {len(data)} rows")

print(f"\nSummary:")
print(f"  total generated: {len(rows)}")
print(f"  shipped (score >= {T} AND validator ok): {len(shipped)} ({len(shipped)/len(rows)*100:.1f}%)")
print(f"  dropped: {len(dropped)} ({len(dropped)/len(rows)*100:.1f}%)")
PYEOF
echo "done"
