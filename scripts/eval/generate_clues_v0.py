#!/usr/bin/env python3
"""Run Mistral-7B-Instruct-v0.3 (via mlx-lm) over data/eval/sample_100_with_definitions.csv
using the prompt template at scripts/eval/prompts/clue_v0.txt. Write outputs to
data/eval/run_v0_results.csv with columns:
    word, length, lemma, pos, definition, synonyms, generated_clue, rating
The `rating` column is left blank for the user to hand-fill (✓ / ◯ / ✗).

Idempotent: rows with a non-empty `generated_clue` in the existing output are
preserved and skipped.

Prerequisites (run once):
    pip install mlx-lm
    python -m mlx_lm.convert --hf-path mistralai/Mistral-7B-Instruct-v0.3 -q
"""

import csv
import sys
from pathlib import Path

MODEL = "mlx-community/Mistral-7B-Instruct-v0.3-4bit"
TEMPERATURE = 0.7
MAX_TOKENS = 24


def load_prompt_template(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def render_prompt(template: str, row: dict[str, str]) -> str:
    return (
        template
        .replace("{word}", row["word"].upper())
        .replace("{pos}", row.get("pos") or "?")
        .replace("{dbnary_definition}", row.get("definition") or "(aucune)")
        .replace("{synonyms_csv}", row.get("synonyms") or "(aucun)")
    )


def first_line(text: str) -> str:
    for line in text.splitlines():
        line = line.strip().lstrip("-•*").strip()
        if line:
            return line
    return ""


def main() -> None:
    try:
        from mlx_lm import generate, load  # type: ignore[import-not-found]
    except ImportError:
        print("mlx-lm not installed. Run: pip install mlx-lm", file=sys.stderr)
        sys.exit(1)

    repo_root = Path(__file__).resolve().parent.parent.parent
    src = repo_root / "data" / "eval" / "sample_100_with_definitions.csv"
    out = repo_root / "data" / "eval" / "run_v0_results.csv"
    template_path = repo_root / "scripts" / "eval" / "prompts" / "clue_v0.txt"

    if not src.exists():
        print(f"missing {src} - run fetch_dbnary_for_sample.py first", file=sys.stderr)
        sys.exit(1)

    template = load_prompt_template(template_path)

    existing: dict[str, dict[str, str]] = {}
    if out.exists():
        with out.open(encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f):
                if row.get("generated_clue"):
                    existing[row["word"]] = row

    with src.open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))

    print(f"loading {MODEL}...", file=sys.stderr)
    model, tokenizer = load(MODEL)

    fieldnames = ["word", "length", "lemma", "pos", "definition", "synonyms", "generated_clue", "rating"]
    enriched: list[dict[str, str]] = []
    for i, row in enumerate(rows, 1):
        word = row["word"]
        if word in existing:
            enriched.append(existing[word])
            continue
        prompt = render_prompt(template, row)
        try:
            raw = generate(
                model, tokenizer,
                prompt=prompt,
                max_tokens=MAX_TOKENS,
                temp=TEMPERATURE,
                verbose=False,
            )
        except TypeError:
            # mlx-lm dropped `temp` in favor of a sampler argument in newer versions.
            from mlx_lm.sample_utils import make_sampler  # type: ignore[import-not-found]
            raw = generate(
                model, tokenizer,
                prompt=prompt,
                max_tokens=MAX_TOKENS,
                sampler=make_sampler(temp=TEMPERATURE),
                verbose=False,
            )
        clue = first_line(raw)
        print(f"  [{i:3d}/{len(rows)}] {word:15s} -> {clue!r}")
        enriched.append({
            "word": word,
            "length": row["length"],
            "lemma": row.get("lemma", ""),
            "pos": row.get("pos", ""),
            "definition": row.get("definition", ""),
            "synonyms": row.get("synonyms", ""),
            "generated_clue": clue,
            "rating": "",
        })

    with out.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        for r in enriched:
            writer.writerow({k: r.get(k, "") for k in fieldnames})

    print(f"\nWrote {len(enriched)} rows to {out.relative_to(repo_root)}")
    print(f"Hand-rate the `rating` column (use: y / borderline / n) then run scripts/eval/eval_clue_quality.py")


if __name__ == "__main__":
    main()
