#!/usr/bin/env python3
"""Generate one lemma-form clue per unique lemma in the sample.

Reads:  data/eval/sample_100_enriched.csv
Writes: data/eval/lemma_clues.csv

Schema:
    lemma, pos, definition, synonyms, lemma_clue, attempts, validation_flag, rating

Each clue is validated against grammalecte: the head content word must be a
citation form AND the same POS class as the target lemma. Failed clues are
regenerated (up to MAX_ATTEMPTS times, escalating temperature). The final
attempt is kept regardless of validation status — `validation_flag` records
the outcome.

Concurrency: a ThreadPoolExecutor lets prompt rendering / validation overlap
with GPU decoding. mlx-lm's `generate()` is not re-entrant on the same model,
so a single generate_lock serialises the actual model call. The throughput
gain is modest but real (retries on one lemma do not block others).
"""

import argparse
import csv
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from morphology_index import MorphologyIndex
from validate_clue import ValidationResult, validate_lemma_clue

MODEL = "mlx-community/Mistral-7B-Instruct-v0.3-4bit"
TOP_P = 0.9
MAX_TOKENS = 20
MAX_ATTEMPTS = 3
ATTEMPT_TEMPERATURES = (0.3, 0.6, 0.9)  # escalate on retries
DEFAULT_WORKERS = 4

# Map French POS labels (from morphology field) to validator's canonical form.
POS_NORMALIZE = {
    "verbe": "verbe",
    "nom": "nom",
    "adjectif": "adj",
    "adj": "adj",
}


def normalize_pos(label: str) -> str:
    return POS_NORMALIZE.get(label.strip().lower(), label.strip().lower())


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
    if not morphology:
        return ""
    return morphology.split(",", 1)[0].strip()


class GenerationContext:
    """Carries the model + tokenizer + lock + helpers shared across worker
    threads so each task is a pure function of (lemma_row, ctx)."""

    def __init__(self, model, tokenizer, generate_fn, sampler_factory, template, index):
        self.model = model
        self.tokenizer = tokenizer
        self.generate = generate_fn
        self.sampler_factory = sampler_factory
        self.template = template
        self.index = index
        self.lock = threading.Lock()


def _generate_once(ctx: GenerationContext, prompt: str, temperature: float) -> str:
    with ctx.lock:
        if ctx.sampler_factory is not None:
            sampler = ctx.sampler_factory(temp=temperature, top_p=TOP_P)
            raw = ctx.generate(
                ctx.model, ctx.tokenizer,
                prompt=prompt, max_tokens=MAX_TOKENS,
                sampler=sampler, verbose=False,
            )
        else:
            raw = ctx.generate(
                ctx.model, ctx.tokenizer,
                prompt=prompt, max_tokens=MAX_TOKENS,
                temp=temperature, top_p=TOP_P, verbose=False,
            )
    return clean_first_line(raw)


def process_lemma(row: dict[str, str], ctx: GenerationContext) -> dict[str, str]:
    lemma = row["lemma"]
    target_pos_validator = normalize_pos(row["pos"])
    prompt = render_prompt(ctx.template, lemma, row["pos"], row["definition"], row["synonyms"], ctx.tokenizer)

    last_clue = ""
    last_result: ValidationResult | None = None
    attempts = 0
    for attempt in range(MAX_ATTEMPTS):
        attempts = attempt + 1
        temp = ATTEMPT_TEMPERATURES[min(attempt, len(ATTEMPT_TEMPERATURES) - 1)]
        clue = _generate_once(ctx, prompt, temp)
        last_clue = clue
        last_result = validate_lemma_clue(clue, lemma, target_pos_validator, ctx.index)
        if last_result.flag == "ok":
            break

    flag = last_result.flag if last_result else "no-attempt"
    return {
        **row,
        "lemma_clue": last_clue,
        "attempts": str(attempts),
        "validation_flag": flag,
        "rating": "",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lexique", type=Path, default=None,
                        help="grammalecte lexique path (default: ~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt)")
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS)
    parser.add_argument("--limit", type=int, default=None,
                        help="cap on number of lemmas processed (for quick iteration)")
    args = parser.parse_args()

    try:
        from mlx_lm import generate, load  # type: ignore[import-not-found]
    except ImportError:
        print("mlx-lm not installed. Activate your venv and run: pip install mlx-lm", file=sys.stderr)
        sys.exit(1)

    sampler_factory = None
    try:
        from mlx_lm.sample_utils import make_sampler  # type: ignore[import-not-found]
        sampler_factory = make_sampler
    except ImportError:
        pass  # older mlx-lm uses temp= kwarg directly

    repo_root = Path(__file__).resolve().parent.parent.parent
    src = repo_root / "data" / "eval" / "sample_100_enriched.csv"
    out = repo_root / "data" / "eval" / "lemma_clues.csv"
    template_path = repo_root / "scripts" / "eval" / "prompts" / "clue_lemma_v0.txt"
    lexique_path = args.lexique or Path(os.path.expanduser("~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt"))

    if not src.exists():
        print(f"missing {src}", file=sys.stderr)
        sys.exit(1)
    if not lexique_path.exists():
        print(f"missing lexique: {lexique_path}", file=sys.stderr)
        sys.exit(1)

    template = template_path.read_text(encoding="utf-8")

    print(f"loading morphology index from {lexique_path}...", file=sys.stderr)
    index = MorphologyIndex.load(lexique_path)

    # Resume: keep only rows where the previous run produced a passing clue.
    # Failed/legacy rows (no validation_flag column) are regenerated.
    existing: dict[str, dict[str, str]] = {}
    if out.exists():
        with out.open(encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f):
                if row.get("lemma_clue") and row.get("validation_flag") == "ok":
                    existing[row["lemma"]] = row

    with src.open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))

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

    if args.limit:
        by_lemma = dict(list(by_lemma.items())[: args.limit])

    todo = [r for lemma, r in by_lemma.items() if lemma not in existing]
    cached = [existing[l] for l in by_lemma if l in existing]

    print(f"unique lemmas: {len(by_lemma)} (todo: {len(todo)}, cached: {len(cached)})", file=sys.stderr)
    print(f"loading {MODEL}...", file=sys.stderr)
    model, tokenizer = load(MODEL)

    ctx = GenerationContext(model, tokenizer, generate, sampler_factory, template, index)

    fieldnames = ["lemma", "pos", "definition", "synonyms", "lemma_clue", "attempts", "validation_flag", "rating"]
    out_rows: list[dict[str, str]] = list(cached)

    started = time.time()
    completed = 0
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(process_lemma, r, ctx): r for r in todo}
        for fut in as_completed(futures):
            res = fut.result()
            out_rows.append(res)
            completed += 1
            elapsed = time.time() - started
            print(
                f"  [{completed:3d}/{len(todo)}] {res['lemma']:20s} ({res['pos']:10s}) "
                f"x{res['attempts']} {res['validation_flag']:14s} -> {res['lemma_clue']!r}  "
                f"({elapsed:.1f}s)"
            )

    out_rows.sort(key=lambda r: r["lemma"])
    with out.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        for r in out_rows:
            writer.writerow({k: r.get(k, "") for k in fieldnames})

    counts: dict[str, int] = {}
    for r in out_rows:
        counts[r["validation_flag"]] = counts.get(r["validation_flag"], 0) + 1
    print(f"\nWrote {len(out_rows)} rows to {out.relative_to(repo_root)} in {time.time()-started:.1f}s")
    for flag, n in sorted(counts.items(), key=lambda kv: -kv[1]):
        print(f"  {flag:18s} {n}")
    print("\nHand-rate the `rating` column then run:")
    print("  python scripts/eval/eval_clue_quality.py --input data/eval/lemma_clues.csv")


if __name__ == "__main__":
    main()
