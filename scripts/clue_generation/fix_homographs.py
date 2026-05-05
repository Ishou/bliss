#!/usr/bin/env python3
"""Re-generate clues for homograph lemmas with explicit POS hint based
on dominant grammalecte frequency.

Problem: 12.5% of shipped lemmas are noun+verb homographs (e.g. change
= currency exchange noun OR 1sg of changer). The bare-lemma prompt
sometimes gets the wrong sense (change → "Mutation notable" — verb
sense applied to noun lemma).

Fix: detect homographs, look up dominant POS by Total occurrences in
the lexique, prompt iter10 with `[nom]` or `[verbe]` hint. The LoRA
was trained on POS-hinted examples too, so it responds to the hint.

Side-by-side: keeps the original raw_csv intact, writes a new column
`fixed_clue` and `fixed_score` so we can compare before replacing.

Usage:
    python scripts/clue_generation/fix_homographs.py
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts" / "eval"))
from morphology_index import MorphologyIndex  # noqa: E402
from validate_clue import validate_lemma_clue  # noqa: E402


def lemma_pos_freq(lexique: Path) -> dict[tuple[str, str], int]:
    """Sum Total occurrences across all surface rows per (lemma, pos_class).
    pos_class collapses verbe paradigms (v0..v3 → verbe), keeps nom/adj/adv."""
    out: dict[tuple[str, str], int] = defaultdict(int)
    with lexique.open(encoding="utf-8") as f:
        seen_header = False
        l_idx = e_idx = t_idx = -1
        for line in f:
            if line.startswith("#"):
                continue
            cols = line.rstrip("\n").split("\t")
            if not seen_header:
                if cols[:1] == ["id"] and "Lemme" in cols and "Total occurrences" in cols:
                    l_idx = cols.index("Lemme")
                    e_idx = cols.index("Étiquettes")
                    t_idx = cols.index("Total occurrences")
                    seen_header = True
                continue
            if len(cols) <= max(l_idx, e_idx, t_idx):
                continue
            tags = cols[e_idx].split()
            pos_class = ""
            for t in tags:
                if t == "nom":
                    pos_class = "nom"
                    break
                if t == "adj":
                    pos_class = "adj"
                if t == "adv":
                    pos_class = "adv"
                if t.startswith("v") and len(t) > 1 and t[1].isdigit():
                    pos_class = "verbe"
                    break
            if not pos_class:
                continue
            try:
                freq = int(cols[t_idx])
            except ValueError:
                continue
            out[(cols[l_idx].lower(), pos_class)] += freq
    return out


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", type=Path,
                        default=REPO / "data" / "eval" / "production" / "lemma_clues_raw.csv")
    parser.add_argument("--model", default="mlx-community/c4ai-command-r-08-2024-4bit")
    parser.add_argument("--adapter", type=Path, default=REPO / "models" / "lora-clue-v3")
    parser.add_argument("--filter", type=Path, default=REPO / "models" / "filter-camembert-v5")
    parser.add_argument("--lexique", type=Path,
                        default=Path(os.path.expanduser(
                            "~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt")))
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--max-tokens", type=int, default=24)
    args = parser.parse_args()

    print("loading morphology index...", file=sys.stderr)
    index = MorphologyIndex.load(args.lexique)
    print("computing lemma-pos frequencies...", file=sys.stderr)
    freq = lemma_pos_freq(args.lexique)

    # Load raw and find homograph lemmas
    with args.raw.open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    print(f"loaded {len(rows)} rows", file=sys.stderr)

    homographs: list[tuple[dict, str]] = []   # (row, dominant_pos)
    for r in rows:
        lemma = r["lemma"]
        classes = index.pos_classes_of_form(lemma)
        # Only fix if it's a noun+verb homograph (the case that matters)
        if not ("nom" in classes and "verbe" in classes):
            continue
        # Also include adj-classes if relevant
        nom_f = freq.get((lemma, "nom"), 0)
        verb_f = freq.get((lemma, "verbe"), 0)
        # A homograph is "ambiguous" if both senses have substantial presence
        # AND the noun is clearly dominant. We pick the dominant one.
        if nom_f >= verb_f:
            dominant = "nom"
        else:
            dominant = "verbe"
        homographs.append((r, dominant))

    print(f"homographs to re-generate: {len(homographs)}", file=sys.stderr)
    by_pos = defaultdict(int)
    for _, p in homographs:
        by_pos[p] += 1
    print(f"  dominant pos: {dict(by_pos)}", file=sys.stderr)

    # Load generator
    print(f"loading generator + adapter {args.adapter}...", file=sys.stderr)
    from mlx_lm import load, batch_generate
    from mlx_lm.sample_utils import make_sampler
    gen_model, tokenizer = load(args.model, adapter_path=str(args.adapter))
    sampler = make_sampler(temp=0.3)

    # Build prompts with POS hint
    pos_label = {"nom": "nom", "verbe": "verbe"}
    items = []
    for r, dominant in homographs:
        lemma = r["lemma"]
        prompt_text = f"Génère une définition mots-fléchés courte pour: {lemma.upper()} [{pos_label[dominant]}]"
        chat = tokenizer.apply_chat_template(
            [{"role": "user", "content": prompt_text}],
            tokenize=True, add_generation_prompt=True,
        )
        items.append({"row": r, "dominant": dominant, "prompt": chat})

    # Batched generate
    new_clues = []
    for bi in range(0, len(items), args.batch_size):
        batch = items[bi : bi + args.batch_size]
        prompts = [b["prompt"] for b in batch]
        try:
            response = batch_generate(gen_model, tokenizer, prompts,
                                       max_tokens=args.max_tokens, sampler=sampler,
                                       verbose=False)
            outputs = response.texts
        except Exception:
            outputs = []
            for b in batch:
                from mlx_lm import generate
                chat = tokenizer.apply_chat_template(
                    [{"role": "user", "content":
                      f"Génère une définition mots-fléchés courte pour: "
                      f"{b['row']['lemma'].upper()} [{pos_label[b['dominant']]}]"}],
                    tokenize=False, add_generation_prompt=True,
                )
                outputs.append(generate(gen_model, tokenizer, chat,
                                         max_tokens=args.max_tokens,
                                         sampler=sampler, verbose=False))
        for b, raw in zip(batch, outputs):
            clue = ""
            for line in raw.splitlines():
                line = line.strip().lstrip("-•*➜→>").strip().rstrip(".")
                for pair in ('""', "''", "[]", "()"):
                    if line.startswith(pair[0]) and line.endswith(pair[1]):
                        line = line[1:-1].strip()
                if line:
                    clue = line
                    break
            new_clues.append((b["row"], b["dominant"], clue))
        if bi % (args.batch_size * 10) == 0:
            print(f"  {bi+len(batch)}/{len(items)} done", flush=True, file=sys.stderr)

    # Score new clues with filter
    print("scoring new clues with filter v5...", file=sys.stderr)
    from sentence_transformers import SentenceTransformer
    from sentence_transformers.util import cos_sim
    fmodel = SentenceTransformer(str(args.filter))
    lemmas = [r["lemma"] for r, _, _ in new_clues]
    new_clues_text = [c for _, _, c in new_clues]
    le = fmodel.encode(lemmas, convert_to_tensor=True, show_progress_bar=False, batch_size=64)
    ce = fmodel.encode(new_clues_text, convert_to_tensor=True, show_progress_bar=False, batch_size=64)
    new_scores = [float(cos_sim(a.unsqueeze(0), b.unsqueeze(0)).item()) for a, b in zip(le, ce)]

    # Validate
    new_flags = []
    for (r, dominant, clue), score in zip(new_clues, new_scores):
        vr = validate_lemma_clue(clue, r["lemma"], dominant, index)
        new_flags.append(vr.flag)

    # Diff
    out = REPO / "data" / "eval" / "production" / "homograph_fix.csv"
    with out.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["lemma", "dominant_pos", "old_clue", "old_score", "old_flag",
                    "new_clue", "new_score", "new_flag",
                    "score_delta", "would_replace"])
        replaced = 0
        for (r, dominant, clue), score, flag in zip(new_clues, new_scores, new_flags):
            old_score = float(r.get("filter_score", 0))
            old_flag = r.get("validation_flag", "")
            old_clue = r.get("lemma_clue", "")
            # Replace if new is validator-ok AND (improves score OR same score and different)
            replace = (flag == "ok" and score > old_score)
            if replace: replaced += 1
            w.writerow([r["lemma"], dominant, old_clue, f"{old_score:.4f}", old_flag,
                        clue, f"{score:.4f}", flag,
                        f"{score - old_score:+.4f}",
                        "Y" if replace else "N"])

    print(f"\nwrote {out}")
    print(f"  homographs processed: {len(new_clues)}")
    print(f"  would replace (new is ok + higher score): {replaced}")


if __name__ == "__main__":
    main()
