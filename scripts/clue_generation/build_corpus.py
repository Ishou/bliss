#!/usr/bin/env python3
"""Aggregate hand-rated `y` clues into mlx-lm fine-tune JSONL.

Sources (in priority order):
- `data/curated/fr.csv` — CC0-licensed seed corpus (clue column).
- `data/eval/lemma_clues_iter{3..7}.csv` — hand-rated outputs from the
  iter3-iter7 eval cycle. Multiple iters contribute different valid
  clues per lemma (variation, not duplication).

Dedup is on `(lemma, lower(clue))` only — different valid clues for the
same lemma are kept as separate training examples.

License hygiene: the corpus contains NO DBnary text (no definitions,
no synonym strings beyond the lemma itself). Curated data is CC0;
hand-rated outputs are independent creative works derived from prompts
that contained DBnary context, but the model OUTPUT is a new work.

Held-out lemma split: shuffle lemmas with seed 20260504, take first 80
as test. Training never sees test lemmas. 30 random pairs from train
go to validation.

Usage:
    python scripts/clue_generation/build_corpus.py
"""

from __future__ import annotations

import csv
import json
import random
from collections import Counter
from pathlib import Path


REPO = Path(__file__).resolve().parent.parent.parent
OUT_DIR = REPO / "data" / "lora"
TRAIN = OUT_DIR / "train.jsonl"
VALID = OUT_DIR / "valid.jsonl"
TEST = OUT_DIR / "test.jsonl"
SEED = 20260504
HELD_OUT = 20          # test lemmas (was 80; dropped because filtering
                       # MIN_LEMMA_LEN ≥ 4 leaves only ~80 unique lemmas
                       # total, most of them already rated across iters)
VALID_SIZE = 20        # validation pairs (sampled from train)
MIN_LEMMA_LEN = 4      # drop L2/L3 chemical-symbol entries that
                       # contaminated iter8 (Symbole du X pattern over-
                       # generalized to longer lemmas). Set to 0 to
                       # include them.


def render_user(lemma: str, pos: str) -> str:
    """Short prompt — the LoRA learns the format from training examples,
    so we don't need anti-pattern exemplars at inference. Compare to
    iter7's ~1500-token v1 prompt (data/eval/prompts/clue_lemma_v1.txt)."""
    pos = (pos or "").strip()
    if pos:
        return f"Génère une définition mots-fléchés courte pour: {lemma.upper()} [{pos}]"
    return f"Génère une définition mots-fléchés courte pour: {lemma.upper()}"


def load_pairs() -> list[tuple[str, str, str, str]]:
    """Yield (lemma, clue, pos, source) for every training-eligible row."""
    pairs: list[tuple[str, str, str, str]] = []

    # CC0 curated seed
    curated = REPO / "data" / "curated" / "fr.csv"
    if curated.exists():
        with curated.open(encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                word = r.get("word", "").strip().lower()
                clue = r.get("clue", "").strip()
                if not word or not clue or clue.lower() == word:
                    continue
                pairs.append(
                    ((r.get("lemma") or word).strip().lower(), clue, "", "curated")
                )

    # iter3..7 hand-rated y
    for iter_path in sorted((REPO / "data" / "eval").glob("lemma_clues_iter*.csv")):
        if not iter_path.name.startswith("lemma_clues_iter"):
            continue
        with iter_path.open(encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                if (r.get("rating") or "").strip().lower() != "y":
                    continue
                lemma = (r.get("lemma") or "").strip().lower()
                clue = (r.get("lemma_clue") or "").strip()
                if not lemma or not clue:
                    continue
                pairs.append((lemma, clue, (r.get("pos") or "").strip(), iter_path.stem))

    # Synthetic Claude-authored CC0 corpus (data/lora/synthetic_clues.csv).
    synth = REPO / "data" / "lora" / "synthetic_clues.csv"
    if synth.exists():
        with synth.open(encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                if (r.get("rating") or "").strip().lower() != "y":
                    continue
                lemma = (r.get("lemma") or "").strip().lower()
                clue = (r.get("lemma_clue") or "").strip()
                if not lemma or not clue:
                    continue
                pairs.append((lemma, clue, (r.get("pos") or "").strip(), "claude-synth"))

    return pairs


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    pairs = load_pairs()

    # Drop very short lemmas (chemical symbols, abbreviations) — they
    # contaminate longer-lemma generation by training a length-conditioned
    # "Symbole du X" pattern. Filter is lemma-length based.
    pairs = [p for p in pairs if len(p[0]) >= MIN_LEMMA_LEN]

    # Dedup on (lemma, lower(clue))
    seen: set[tuple[str, str]] = set()
    unique: list[tuple[str, str, str, str]] = []
    for p in pairs:
        key = (p[0], p[1].lower())
        if key in seen:
            continue
        seen.add(key)
        unique.append(p)

    by_source = Counter(p[3] for p in unique)
    print(f"raw pairs: {len(pairs)}")
    print(f"unique (lemma,clue): {len(unique)}")
    print(f"unique lemmas: {len(set(p[0] for p in unique))}")
    print(f"by source: {dict(by_source)}")

    # Hold out by lemma
    rng = random.Random(SEED)
    all_lemmas = sorted(set(p[0] for p in unique))
    rng.shuffle(all_lemmas)
    test_lemmas = set(all_lemmas[:HELD_OUT])
    train_pairs = [p for p in unique if p[0] not in test_lemmas]
    test_pairs = [p for p in unique if p[0] in test_lemmas]

    # Sample validation from train
    rng.shuffle(train_pairs)
    valid_pairs = train_pairs[:VALID_SIZE]
    final_train = train_pairs[VALID_SIZE:]

    print(
        f"\ntrain={len(final_train)} valid={len(valid_pairs)} test={len(test_pairs)} "
        f"(held-out lemmas={len(test_lemmas)})"
    )

    def write(path: Path, ps: list[tuple[str, str, str, str]]) -> None:
        with path.open("w", encoding="utf-8") as f:
            for lemma, clue, pos, _ in ps:
                f.write(
                    json.dumps(
                        {
                            "messages": [
                                {"role": "user", "content": render_user(lemma, pos)},
                                {"role": "assistant", "content": clue},
                            ]
                        },
                        ensure_ascii=False,
                    )
                    + "\n"
                )

    write(TRAIN, final_train)
    write(VALID, valid_pairs)
    write(TEST, test_pairs)
    print(f"\nwrote {TRAIN.relative_to(REPO)}, {VALID.name}, {TEST.name}")


if __name__ == "__main__":
    main()
