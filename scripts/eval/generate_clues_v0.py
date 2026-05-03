#!/usr/bin/env python3
"""Run Mistral-7B-Instruct-v0.3 (via mlx-lm) over the morphology-enriched sample
using the prompt template at scripts/eval/prompts/clue_v0.txt. Write outputs to
data/eval/run_v0_results.csv with columns:
    word, length, lemma, pos, morphology, definition, synonyms, generated_clue, flag, rating
The `rating` column is left blank for the user to hand-fill (y / b / n).

`flag` is auto-set when the post-filter rejects the model output (e.g. clue
echoes the surface or the lemma) so review can prioritise those.

Idempotent: rows with a non-empty `generated_clue` in the existing output are
preserved and skipped.

Prerequisites (run once):
    pip install mlx-lm
    # First run downloads the 4-bit Mistral weights (~4 GB).
"""

import csv
import sys
import unicodedata
from pathlib import Path

MODEL = "mlx-community/Mistral-7B-Instruct-v0.3-4bit"
TEMPERATURE = 0.3
MAX_TOKENS = 20
TOP_P = 0.9


def _strip_accents(s: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")


def _normalize(s: str) -> str:
    return _strip_accents(s).lower().strip()


def load_prompt_template(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def render_user_content(template: str, row: dict[str, str]) -> str:
    return (
        template
        .replace("{word_upper}", row["word"].upper())
        .replace("{lemma}", row.get("lemma") or row["word"])
        .replace("{morphology}", row.get("morphology") or "?")
        .replace("{dbnary_definition}", row.get("definition") or "(aucune)")
        .replace("{synonyms_csv}", row.get("synonyms") or "(aucun)")
    )


def render_prompt(template: str, row: dict[str, str], tokenizer) -> str:
    """Apply the model's chat template so Mistral-Instruct treats the input as
    an instruction, not autocomplete material. Without this the model rambles
    in markdown/English and ignores the rules."""
    user_content = render_user_content(template, row)
    messages = [{"role": "user", "content": user_content}]
    return tokenizer.apply_chat_template(
        messages,
        tokenize=False,
        add_generation_prompt=True,
    )


def clean_first_line(text: str) -> str:
    for line in text.splitlines():
        line = line.strip().lstrip("-•*➜→>").strip().rstrip(".")
        # Strip surrounding quotes / brackets the model sometimes adds.
        for pair in ('""', "''", "[]", "()"):
            if line.startswith(pair[0]) and line.endswith(pair[1]):
                line = line[1:-1].strip()
        if line:
            return line
    return ""


def post_filter(clue: str, word: str, lemma: str) -> str:
    """Return a flag string if clue should be flagged for review, else ''."""
    if not clue:
        return "empty"
    n_clue = _normalize(clue)
    n_word = _normalize(word)
    n_lemma = _normalize(lemma) if lemma else ""
    # Reject if clue contains the surface form as a substring.
    if n_word and n_word in n_clue.split():
        return "echoes-surface"
    # Reject if clue is the lemma when surface differs (the inflection bug
    # that motivated the morphology work).
    if n_lemma and n_lemma != n_word and n_clue == n_lemma:
        return "echoes-lemma"
    if len(clue.split()) > 8:
        return "too-long"
    return ""


def main() -> None:
    try:
        from mlx_lm import generate, load  # type: ignore[import-not-found]
    except ImportError:
        print("mlx-lm not installed. Activate your venv and run: pip install mlx-lm", file=sys.stderr)
        sys.exit(1)

    repo_root = Path(__file__).resolve().parent.parent.parent
    enriched_path = repo_root / "data" / "eval" / "sample_100_enriched.csv"
    fallback_path = repo_root / "data" / "eval" / "sample_100_with_definitions.csv"
    src = enriched_path if enriched_path.exists() else fallback_path
    out = repo_root / "data" / "eval" / "run_v0_results.csv"
    template_path = repo_root / "scripts" / "eval" / "prompts" / "clue_v0.txt"

    if not src.exists():
        print(f"missing {src} - run fetch_dbnary_for_sample.py + enrich_with_morphology.py first", file=sys.stderr)
        sys.exit(1)
    if src == fallback_path:
        print("WARNING: sample_100_enriched.csv missing; running without morphology", file=sys.stderr)

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

    fieldnames = [
        "word", "length", "lemma", "pos", "morphology",
        "definition", "synonyms", "generated_clue", "flag", "rating",
    ]
    enriched: list[dict[str, str]] = []
    flagged = 0
    for i, row in enumerate(rows, 1):
        word = row["word"]
        if word in existing:
            enriched.append(existing[word])
            continue
        prompt = render_prompt(template, row, tokenizer)
        try:
            raw = generate(
                model, tokenizer,
                prompt=prompt,
                max_tokens=MAX_TOKENS,
                temp=TEMPERATURE,
                top_p=TOP_P,
                verbose=False,
            )
        except TypeError:
            # Newer mlx-lm replaced `temp`/`top_p` with a sampler argument.
            from mlx_lm.sample_utils import make_sampler  # type: ignore[import-not-found]
            raw = generate(
                model, tokenizer,
                prompt=prompt,
                max_tokens=MAX_TOKENS,
                sampler=make_sampler(temp=TEMPERATURE, top_p=TOP_P),
                verbose=False,
            )
        clue = clean_first_line(raw)
        flag = post_filter(clue, word, row.get("lemma", ""))
        if flag:
            flagged += 1
        marker = f" [{flag}]" if flag else ""
        print(f"  [{i:3d}/{len(rows)}] {word:18s} ({row.get('morphology','?')[:40]:40s}) -> {clue!r}{marker}")
        enriched.append({
            "word": word,
            "length": row["length"],
            "lemma": row.get("lemma", ""),
            "pos": row.get("pos", ""),
            "morphology": row.get("morphology", ""),
            "definition": row.get("definition", ""),
            "synonyms": row.get("synonyms", ""),
            "generated_clue": clue,
            "flag": flag,
            "rating": "",
        })

    with out.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        for r in enriched:
            writer.writerow({k: r.get(k, "") for k in fieldnames})

    print(f"\nWrote {len(enriched)} rows to {out.relative_to(repo_root)} ({flagged} flagged by post-filter)")
    print("Hand-rate the `rating` column (y / b / n) then run: python scripts/eval/eval_clue_quality.py")


if __name__ == "__main__":
    main()
