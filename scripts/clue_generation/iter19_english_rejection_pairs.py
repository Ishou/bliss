#!/usr/bin/env python3
"""Loader: reads hand-authored DPO pairs for iter19 from the JSONL data file.

Data: data/lora_dpo_iter19/iter19_authored_pairs.jsonl
Each record: {lemma, pos, bucket, chosen: [...], rejected: [...]}
Two buckets — WHOLLY_ENGLISH_CLUE and MIXED_LANGUAGE_LEAK — covering 58 lemmas.

all_pairs() yields cartesian (lemma, pos, chosen, rejected) tuples, same
contract as iter18_authored_pairs.all_pairs() so build_iter19_corpus.py
can concatenate both sources without bespoke logic.
"""
from __future__ import annotations
import json
from pathlib import Path

_DATA = Path(__file__).resolve().parent.parent.parent / "data" / "lora_dpo_iter19" / "iter19_authored_pairs.jsonl"


def all_pairs():
    """Yield (lemma, pos, chosen, rejected) from the cartesian product of each record."""
    with _DATA.open(encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            rec = json.loads(line)
            for c in rec["chosen"]:
                for r in rec["rejected"]:
                    yield rec["lemma"], rec["pos"], c, r


if __name__ == "__main__":
    from collections import Counter
    counts: Counter[str] = Counter()
    lemma_counts: Counter[str] = Counter()
    for lemma, pos, c, r in all_pairs():
        counts[pos] += 1
        lemma_counts[lemma] += 1
    print(f"total pairs: {sum(counts.values())}")
    print(f"unique lemmas: {len(lemma_counts)}")
    for pos, n in counts.most_common():
        print(f"  {pos}: {n}")
