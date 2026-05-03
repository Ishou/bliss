#!/usr/bin/env python3
"""Generate one lemma-form clue per unique lemma in the sample, batched.

Reads:  data/eval/sample_100_enriched.csv
Writes: data/eval/lemma_clues.csv

Schema:
    lemma, pos, definition, synonyms, lemma_clue, attempts, validation_flag, rating

Pipeline per round:
1. Render prompts for every lemma still pending.
2. Run them through mlx-lm's batch_generate (real GPU-side batching, not
   thread overlap).
3. Validate each output. Passing clues are kept; failures are queued for the
   next round at a higher sampling temperature.
4. Stop when all lemmas pass or MAX_ATTEMPTS rounds have elapsed; the last
   output is kept either way and the validation_flag records the outcome.

Validation requires the clue's first content-word head to be (a) a citation
form in grammalecte and (b) the same POS class as the target lemma.
"""

import argparse
import csv
import os
import sys
import time
from pathlib import Path

from morphology_index import MorphologyIndex
from validate_clue import ValidationResult, validate_lemma_clue

MODEL = "mlx-community/Mistral-7B-Instruct-v0.3-4bit"
TOP_P = 0.9
MAX_TOKENS = 20
MAX_ATTEMPTS = 3
ATTEMPT_TEMPERATURES = (0.3, 0.6, 0.9)
DEFAULT_BATCH_SIZE = 8

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


def _resolve_batch_generate():
    """Return (batch_fn, sampler_factory) tuple. batch_fn signature is
        batch_fn(model, tokenizer, prompts: list[str], *, max_tokens: int, temp: float, top_p: float) -> list[str]
    Tries mlx-lm's native batched API first; falls back to a serial loop over
    `generate` if not available (older mlx-lm versions) so the script still runs."""
    sampler_factory = None
    try:
        from mlx_lm.sample_utils import make_sampler  # type: ignore[import-not-found]
        sampler_factory = make_sampler
    except ImportError:
        pass

    # Modern mlx-lm: top-level batch_generate.
    try:
        from mlx_lm import batch_generate as _native  # type: ignore[import-not-found]

        def fn(model, tokenizer, prompts, *, max_tokens, temp, top_p):
            kwargs = {"max_tokens": max_tokens, "verbose": False}
            if sampler_factory is not None:
                kwargs["sampler"] = sampler_factory(temp=temp, top_p=top_p)
            else:
                kwargs["temp"] = temp
                kwargs["top_p"] = top_p
            return _native(model, tokenizer, prompts=prompts, **kwargs)

        return fn, "native"
    except ImportError:
        pass

    # Some versions expose it under utils.
    try:
        from mlx_lm.utils import batch_generate as _native  # type: ignore[import-not-found]

        def fn(model, tokenizer, prompts, *, max_tokens, temp, top_p):
            kwargs = {"max_tokens": max_tokens, "verbose": False}
            if sampler_factory is not None:
                kwargs["sampler"] = sampler_factory(temp=temp, top_p=top_p)
            else:
                kwargs["temp"] = temp
                kwargs["top_p"] = top_p
            return _native(model, tokenizer, prompts=prompts, **kwargs)

        return fn, "utils"
    except ImportError:
        pass

    # Fallback: serial loop. Same interface, but no real batching.
    from mlx_lm import generate  # type: ignore[import-not-found]

    def fn(model, tokenizer, prompts, *, max_tokens, temp, top_p):
        outs: list[str] = []
        for p in prompts:
            kwargs = {"max_tokens": max_tokens, "verbose": False}
            if sampler_factory is not None:
                kwargs["sampler"] = sampler_factory(temp=temp, top_p=top_p)
            else:
                kwargs["temp"] = temp
                kwargs["top_p"] = top_p
            outs.append(generate(model, tokenizer, prompt=p, **kwargs))
        return outs

    return fn, "serial-fallback"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lexique", type=Path, default=None)
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    parser.add_argument("--limit", type=int, default=None)
    args = parser.parse_args()

    try:
        from mlx_lm import load  # type: ignore[import-not-found]
    except ImportError:
        print("mlx-lm not installed. Activate your venv and run: pip install mlx-lm", file=sys.stderr)
        sys.exit(1)

    repo_root = Path(__file__).resolve().parent.parent.parent
    src = repo_root / "data" / "eval" / "sample_100_enriched.csv"
    out = repo_root / "data" / "eval" / "lemma_clues.csv"
    template_path = repo_root / "scripts" / "eval" / "prompts" / "clue_lemma_v0.txt"
    lexique_path = args.lexique or Path(os.path.expanduser("~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt"))

    if not src.exists():
        print(f"missing {src}", file=sys.stderr); sys.exit(1)
    if not lexique_path.exists():
        print(f"missing lexique: {lexique_path}", file=sys.stderr); sys.exit(1)

    template = template_path.read_text(encoding="utf-8")

    print(f"loading morphology index from {lexique_path}...", file=sys.stderr)
    index = MorphologyIndex.load(lexique_path)

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
    from mlx_lm import load
    model, tokenizer = load(MODEL)

    batch_fn, mode = _resolve_batch_generate()
    print(f"batch path: {mode}", file=sys.stderr)

    # State: pending[lemma] = (row, attempt_idx, last_clue, last_flag)
    pending: dict[str, tuple[dict, int, str, str]] = {
        r["lemma"]: (r, 0, "", "") for r in todo
    }
    completed: dict[str, dict[str, str]] = {r["lemma"]: r for r in cached}

    started = time.time()
    for round_idx in range(MAX_ATTEMPTS):
        if not pending:
            break
        temp = ATTEMPT_TEMPERATURES[min(round_idx, len(ATTEMPT_TEMPERATURES) - 1)]
        round_lemmas = list(pending.keys())
        print(
            f"\n=== round {round_idx + 1}/{MAX_ATTEMPTS} | temp={temp} | "
            f"{len(round_lemmas)} lemmas | batch={args.batch_size} ===",
            file=sys.stderr,
        )

        for batch_start in range(0, len(round_lemmas), args.batch_size):
            batch = round_lemmas[batch_start : batch_start + args.batch_size]
            prompts = [
                render_prompt(template, l, pending[l][0]["pos"], pending[l][0]["definition"],
                              pending[l][0]["synonyms"], tokenizer)
                for l in batch
            ]
            t0 = time.time()
            outputs = batch_fn(model, tokenizer, prompts,
                               max_tokens=MAX_TOKENS, temp=temp, top_p=TOP_P)
            t1 = time.time()
            for lemma, raw in zip(batch, outputs):
                row, attempt_idx, _, _ = pending[lemma]
                clue = clean_first_line(raw)
                target_pos = normalize_pos(row["pos"])
                vr = validate_lemma_clue(clue, lemma, target_pos, index)
                attempts = round_idx + 1
                pending[lemma] = (row, attempt_idx, clue, vr.flag)
                if vr.flag == "ok":
                    completed[lemma] = {
                        **row, "lemma_clue": clue,
                        "attempts": str(attempts), "validation_flag": "ok", "rating": "",
                    }
                    pending.pop(lemma)
                    marker = "OK"
                else:
                    marker = vr.flag
                print(f"  [{lemma:20s} ({row['pos']:10s}) x{attempts}] {marker:14s} -> {clue!r}")
            elapsed_batch = t1 - t0
            print(f"  -- batch of {len(batch)} took {elapsed_batch:.1f}s ({len(batch)/max(elapsed_batch,0.001):.1f} clue/s)", file=sys.stderr)

    # Anything left in `pending` after the final round: keep the last attempt with its flag.
    for lemma, (row, _, last_clue, last_flag) in pending.items():
        completed[lemma] = {
            **row, "lemma_clue": last_clue,
            "attempts": str(MAX_ATTEMPTS), "validation_flag": last_flag or "no-attempt",
            "rating": "",
        }

    fieldnames = ["lemma", "pos", "definition", "synonyms", "lemma_clue", "attempts", "validation_flag", "rating"]
    out_rows = sorted(completed.values(), key=lambda r: r["lemma"])
    with out.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        for r in out_rows:
            writer.writerow({k: r.get(k, "") for k in fieldnames})

    counts: dict[str, int] = {}
    for r in out_rows:
        counts[r["validation_flag"]] = counts.get(r["validation_flag"], 0) + 1
    elapsed = time.time() - started
    print(f"\nWrote {len(out_rows)} rows to {out.relative_to(repo_root)} in {elapsed:.1f}s "
          f"({len(todo)/max(elapsed,0.001):.1f} new clue/s)")
    for flag, n in sorted(counts.items(), key=lambda kv: -kv[1]):
        print(f"  {flag:18s} {n}")
    print("\nHand-rate the `rating` column then run:")
    print("  python scripts/eval/eval_clue_quality.py --input data/eval/lemma_clues.csv")


if __name__ == "__main__":
    main()
