#!/usr/bin/env python3
"""Loads iter19 hand-authored DPO pairs from data/lora_dpo_iter19/iter19_authored_pairs.jsonl.
Yields (lemma, pos, chosen, rejected) tuples via all_pairs(); same contract as iter18_authored_pairs.
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
