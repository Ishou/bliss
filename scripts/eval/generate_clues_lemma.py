#!/usr/bin/env python3
"""Generate one lemma-form clue per unique lemma in the sample.

Reads:  data/eval/sample_100_enriched.csv (must have lemma + morphology + dbnary fields)
Writes: data/eval/lemma_clues.csv with columns:
    lemma, pos, definition, synonyms, lemma_clue, flag

Dedupe is by lemma. When multiple surface forms share a lemma, the first row's
DBnary definition + synonyms are used (good enough — DBnary is keyed by lemma
anyway, so they should be consistent).

Pairs with apply_inflections.py to produce the per-surface eval CSV.
"""

import csv
import sys
from pathlib import Path

MODEL = "mlx-community/Mistral-7B-Instruct-v0.3-4bit"
TEMPERATURE = 0.3
MAX_TOKENS = 20
TOP_P = 0.9


def render_user_content(template: str, lemma: str, pos: str, definition: str, synonyms: str) -> str:
    return (
        template
        .replace("{lemma_upper}", lemma.upper())
        .replace("{pos}", pos or "?")
        .replace("{dbnary_definition}", definition or "(aucune)")
        .replace("{synonyms_csv}", synonyms or "(aucun)")
    )


def render_prompt(template: str, lemma: str, pos: str, definition: str, synonyms: str, tokenizer) -> str:
    user = render_user_content(template, lemma, pos, definition, synonyms)
    messages = [{"role": "user", "content": user}]
    return tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)


def clean_first_line(text: str) -> str:
    for line in text.splitlines():
        line = line.strip().lstrip("-•*➜→>").strip().rstrip(".")
        for pair in ('""', "''", "[]", "()"):
            if line.startswith(pair[0]) and line.endswith(pair[1]):
                line = line[1:-1].strip()
        if line:
            return line
    return ""


def pos_label(morphology: str) -> str:
    """First segment of the morphology descriptor is the French POS label."""
    if not morphology:
        return ""
    return morphology.split(",", 1)[0].strip()


def main() -> None:
    try:
        from mlx_lm import generate, load  # type: ignore[import-not-found]
    except ImportError:
        print("mlx-lm not installed. Activate your venv and run: pip install mlx-lm", file=sys.stderr)
        sys.exit(1)

    repo_root = Path(__file__).resolve().parent.parent.parent
    src = repo_root / "data" / "eval" / "sample_100_enriched.csv"
    out = repo_root / "data" / "eval" / "lemma_clues.csv"
    template_path = repo_root / "scripts" / "eval" / "prompts" / "clue_lemma_v0.txt"

    if not src.exists():
        print(f"missing {src}", file=sys.stderr)
        sys.exit(1)

    template = template_path.read_text(encoding="utf-8")

    # Resume support: skip lemmas already cued.
    existing: dict[str, dict[str, str]] = {}
    if out.exists():
        with out.open(encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f):
                if row.get("lemma_clue"):
                    existing[row["lemma"]] = row

    with src.open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))

    # Dedupe by lemma; keep first row's DBnary fields.
    by_lemma: dict[str, dict[str, str]] = {}
    for r in rows:
        lemma = (r.get("lemma") or r["word"]).strip().lower()
        if not lemma or lemma in by_lemma:
            continue
        by_lemma[lemma] = {
            "lemma": lemma,
            "pos": pos_label(r.get("morphology", "")),
            "definition": r.get("definition", ""),
            "synonyms": r.get("synonyms", ""),
        }

    print(f"unique lemmas: {len(by_lemma)} (from {len(rows)} surface forms)", file=sys.stderr)
    print(f"loading {MODEL}...", file=sys.stderr)
    model, tokenizer = load(MODEL)

    fieldnames = ["lemma", "pos", "definition", "synonyms", "lemma_clue", "flag"]
    out_rows: list[dict[str, str]] = []
    for i, (lemma, row) in enumerate(by_lemma.items(), 1):
        if lemma in existing:
            out_rows.append(existing[lemma])
            continue
        prompt = render_prompt(template, lemma, row["pos"], row["definition"], row["synonyms"], tokenizer)
        try:
            raw = generate(
                model, tokenizer,
                prompt=prompt, max_tokens=MAX_TOKENS,
                temp=TEMPERATURE, top_p=TOP_P, verbose=False,
            )
        except TypeError:
            from mlx_lm.sample_utils import make_sampler  # type: ignore[import-not-found]
            raw = generate(
                model, tokenizer,
                prompt=prompt, max_tokens=MAX_TOKENS,
                sampler=make_sampler(temp=TEMPERATURE, top_p=TOP_P),
                verbose=False,
            )
        clue = clean_first_line(raw)
        flag = "" if clue else "empty"
        print(f"  [{i:3d}/{len(by_lemma)}] {lemma:20s} ({row['pos']:10s}) -> {clue!r}")
        out_rows.append({**row, "lemma_clue": clue, "flag": flag})

    with out.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        for r in out_rows:
            writer.writerow({k: r.get(k, "") for k in fieldnames})

    print(f"\nWrote {len(out_rows)} rows to {out.relative_to(repo_root)}")
    print("Next: python scripts/eval/apply_inflections.py")


if __name__ == "__main__":
    main()
