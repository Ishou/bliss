#!/usr/bin/env python3
"""Targeted iter13 eval over 100 held-out PP-productive verbs.

Sample 100 PP-productive verbs that were NOT in the iter13 DPO corpus
(no train / valid / test exposure). Generate a lemma clue for each via
iter13's adapter. Inflate to the PP-fem-sg surface form. Run
validate_lemma_clue, run inflect_clue, classify the outcome.

Output: data/eval/iter13_pp_eval.csv with one row per verb.

Self-rating column is left blank — to be hand-filled. The script also
prints a summary breakdown of inflection_status counts so the gross
shape is visible without rating.

Usage:
    python scripts/eval/iter13_pp_eval.py \\
        --adapter models/lora-clue-v6 \\
        --pp-list data/eval/pp_productive_verbs.csv \\
        --corpus data/lora_dpo_iter13/build_corpus.py \\
        --output data/eval/iter13_pp_eval.csv \\
        --n 100 --seed 20260505
"""
from __future__ import annotations

import argparse
import csv
import os
import random
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from inflect_clue import inflect_clue
from morphology_index import MorphologyIndex
from validate_clue import validate_lemma_clue


PROMPT_TMPL = "Génère une définition mots-fléchés courte pour: {} [verbe]"


def _load_corpus_lemmas(corpus_py: Path) -> set[str]:
    """Extract verb lemmas already in the iter13 DPO corpus, by parsing the
    Python `PAIRS` literal from build_corpus.py. Pure-text scan — no exec."""
    text = corpus_py.read_text(encoding="utf-8")
    return {m.group(1).lower() for m in re.finditer(r'\("([^"]+)",\s*"', text)}


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--adapter", required=True,
                   help="adapter dir for mlx_lm.load")
    p.add_argument("--pp-list", type=Path, required=True)
    p.add_argument("--corpus", type=Path, required=True,
                   help="data/lora_dpo_iter13/build_corpus.py — to exclude lemmas")
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--lexique", type=Path, default=None)
    p.add_argument("--n", type=int, default=100)
    p.add_argument("--seed", type=int, default=20260505)
    p.add_argument("--max-tokens", type=int, default=24)
    p.add_argument("--temp", type=float, default=0.3)
    args = p.parse_args()

    lex = args.lexique or Path(os.path.expanduser(
        "~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt"))

    excluded = _load_corpus_lemmas(args.corpus)
    print(f"excluding {len(excluded)} corpus lemmas from sample", file=sys.stderr)

    pool: list[str] = []
    with args.pp_list.open(encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            if r["lemma"] in excluded:
                continue
            pool.append(r["lemma"])
    print(f"pool size: {len(pool)} held-out PP-productive verbs", file=sys.stderr)

    rng = random.Random(args.seed)
    sample = rng.sample(pool, min(args.n, len(pool)))

    # Load morphology index up-front (fast).
    print(f"loading morphology…", file=sys.stderr)
    idx = MorphologyIndex.load(lex)

    # Load model + adapter.
    from mlx_lm import generate, load
    print(f"loading model + adapter at {args.adapter}…", file=sys.stderr)
    model, tok = load("mlx-community/c4ai-command-r-08-2024-4bit",
                      adapter_path=args.adapter)

    rows = []
    for i, lemma in enumerate(sample, 1):
        msg = [{"role": "user", "content": PROMPT_TMPL.format(lemma.upper())}]
        prompt = tok.apply_chat_template(msg, tokenize=False,
                                          add_generation_prompt=True)
        # Default sampler is greedy; pass temp via sampler if needed.
        try:
            out = generate(model, tok, prompt=prompt,
                           max_tokens=args.max_tokens, verbose=False).strip()
        except Exception as e:
            out = f"<ERR:{type(e).__name__}>"
        # First line / first 12 words.
        clue = out.split("\n")[0].strip()
        clue = " ".join(clue.split()[:12])

        # Find a PP-fem-sg surface for this lemma.
        pp_surface = None
        target_tags = None
        for surface, tags in idx.by_lemma.get(lemma, []):
            if "ppas" in tags and "fem" in tags and "sg" in tags:
                pp_surface = surface
                target_tags = tags
                break
        if not pp_surface:
            for surface, tags in idx.by_lemma.get(lemma, []):
                if "ppas" in tags:
                    pp_surface = surface
                    target_tags = tags
                    break

        # Validate the lemma clue.
        v = validate_lemma_clue(clue, lemma, "verbe", idx)
        validation_flag = v.flag

        # Inflate to PP form.
        infl_text = ""
        infl_flag = ""
        if validation_flag == "ok" and target_tags:
            res = inflect_clue(clue, set(target_tags), idx)
            infl_text = res.text
            infl_flag = res.flag

        rows.append({
            "lemma": lemma,
            "lemma_clue": clue,
            "validation_flag": validation_flag,
            "pp_surface": pp_surface or "",
            "inflected_clue": infl_text,
            "inflection_status": infl_flag or "inflected",
            "rating": "",  # to be hand-filled: y / b / n
        })
        print(f"[{i:3d}/{len(sample)}] {lemma:20s} -> {clue[:40]:40s}"
              f" | flag={validation_flag:18s} infl={infl_flag or '-':18s}",
              file=sys.stderr)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = ["lemma", "lemma_clue", "validation_flag", "pp_surface",
                  "inflected_clue", "inflection_status", "rating"]
    with args.output.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=fieldnames, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)

    # Summary.
    from collections import Counter
    val_c = Counter(r["validation_flag"] for r in rows)
    inf_c = Counter(r["inflection_status"] for r in rows)
    print(f"\nvalidation: {dict(val_c)}", file=sys.stderr)
    print(f"inflection: {dict(inf_c)}", file=sys.stderr)
    print(f"wrote {args.output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
