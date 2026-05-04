#!/usr/bin/env python3
"""Generate one lemma-form clue per unique lemma in the sample, via Ollama.

Reads:  data/eval/sample_100_enriched.csv
Writes: data/eval/lemma_clues.csv

Schema:
    lemma, pos, definition, synonyms, lemma_clue, attempts, validation_flag, rating

Pipeline per round:
1. Render a chat message for every still-pending lemma.
2. Fire all of them at Ollama's /api/chat endpoint concurrently (the daemon
   does its own continuous batching, so we just feed it as many in-flight
   requests as it'll absorb).
3. Validate each output. Passing clues are kept; failures queue for the next
   round at a higher sampling temperature (0.3 -> 0.6 -> 0.9).
4. Stop when all pass or MAX_ATTEMPTS rounds elapse; the last output is kept
   either way and `validation_flag` records the outcome.

Validation requires the clue's first content-word head to be a citation form
in grammalecte AND the same POS class as the target lemma.

Setup:
    brew install ollama
    ollama serve &           # if not already running as a service
    ollama pull mistral:7b-instruct
"""

import argparse
import csv
import json
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from morphology_index import MorphologyIndex
from validate_clue import ValidationResult, validate_lemma_clue

DEFAULT_MODEL = "mistral-nemo:latest"
DEFAULT_HOST = "http://localhost:11434"
DEFAULT_CONCURRENCY = 8
TOP_P = 0.9
MAX_TOKENS = 24
MAX_ATTEMPTS = 3
ATTEMPT_TEMPERATURES = (0.3, 0.6, 0.9)
HTTP_TIMEOUT = 120
# Cap per-request context. Without this, Ollama allocates KV cache for the
# model's full default context (256k tokens for Mistral-Nemo) × OLLAMA_NUM_PARALLEL,
# which is gigabytes per concurrent request and unusable on a laptop. Our prompts
# (rules + few-shots + per-word slot) are well under 4k tokens.
NUM_CTX = 4096

POS_NORMALIZE = {
    "verbe": "verbe",
    "nom": "nom",
    "adjectif": "adj",
    "adj": "adj",
    "adverbe": "adv",
    "adv": "adv",
}


def normalize_pos(label: str) -> str:
    return POS_NORMALIZE.get(label.strip().lower(), label.strip().lower())


def render_user_content(template: str, lemma: str, pos: str, definition: str, synonyms: str) -> str:
    """`definition` may be a single sense or pipe-delimited multi-sense (the
    enricher emits all clean senses now). Render single sense as-is; render
    multiple as a numbered list so the model can pick the most cluable
    sense instead of being anchored to sense_1."""
    senses = [s.strip() for s in (definition or "").split("|") if s.strip()]
    if len(senses) <= 1:
        rendered_def = senses[0] if senses else "(aucune)"
    else:
        rendered_def = "\n  " + "\n  ".join(f"{i+1}. {s}" for i, s in enumerate(senses))
    return (
        template
        .replace("{lemma_upper}", lemma.upper())
        .replace("{pos}", pos or "?")
        .replace("{dbnary_definition}", rendered_def)
        .replace("{synonyms_csv}", synonyms or "(aucun)")
    )


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


def ollama_health(host: str, model: str) -> tuple[bool, str]:
    try:
        with urllib.request.urlopen(f"{host}/api/tags", timeout=5) as resp:
            data = json.load(resp)
    except (urllib.error.URLError, ConnectionError) as e:
        return False, (
            f"cannot reach Ollama at {host}: {e}\n"
            "  Setup: brew install ollama && ollama serve &"
        )
    models = {m["name"] for m in data.get("models", [])}
    # Ollama treats `name` as shorthand for `name:latest`; mirror that here.
    candidates = {model, model if ":" in model else f"{model}:latest"}
    if not candidates & models:
        return False, (
            f"model {model!r} not pulled. Available: {sorted(models) or '(none)'}\n"
            f"  Pull it with: ollama pull {model}"
        )
    return True, ""


def ollama_chat(host: str, model: str, user_content: str, *, temperature: float, max_tokens: int) -> str:
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": user_content}],
        "options": {
            "temperature": temperature,
            "top_p": TOP_P,
            "num_predict": max_tokens,
            "num_ctx": NUM_CTX,
        },
        "stream": False,
    }).encode("utf-8")
    req = urllib.request.Request(
        f"{host}/api/chat",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
        payload = json.load(resp)
    return payload.get("message", {}).get("content", "")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lexique", type=Path, default=None)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--concurrency", type=int, default=DEFAULT_CONCURRENCY)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--input", type=Path, default=None,
                        help="enriched-sample CSV (default: data/eval/sample_100_enriched.csv)")
    parser.add_argument("--output", type=Path, default=None,
                        help="lemma-clues CSV (default: data/eval/lemma_clues.csv)")
    parser.add_argument("--prompt", type=Path, default=None,
                        help="prompt template (default: scripts/eval/prompts/clue_lemma_v0.txt)")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent.parent
    src = args.input or (repo_root / "data" / "eval" / "sample_100_enriched.csv")
    out = args.output or (repo_root / "data" / "eval" / "lemma_clues.csv")
    template_path = args.prompt or (repo_root / "scripts" / "eval" / "prompts" / "clue_lemma_v0.txt")
    lexique_path = args.lexique or Path(os.path.expanduser("~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt"))

    if not src.exists():
        print(f"missing {src}", file=sys.stderr); sys.exit(1)
    if not lexique_path.exists():
        print(f"missing lexique: {lexique_path}", file=sys.stderr); sys.exit(1)

    ok, reason = ollama_health(args.host, args.model)
    if not ok:
        print(reason, file=sys.stderr)
        sys.exit(1)
    print(f"ollama: {args.host} model={args.model} concurrency={args.concurrency}", file=sys.stderr)

    template = template_path.read_text(encoding="utf-8")

    print(f"loading morphology index from {lexique_path}...", file=sys.stderr)
    index = MorphologyIndex.load(lexique_path)

    # Resume: keep ANY row with a non-empty clue, regardless of validation
    # flag. For long batch runs we'd rather accept a previously-produced
    # stem-leak clue than burn 3 retry rounds again on restart.
    existing: dict[str, dict[str, str]] = {}
    if out.exists():
        with out.open(encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f):
                if row.get("lemma_clue"):
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

    pending: dict[str, dict[str, str]] = {r["lemma"]: r for r in todo}
    last_output: dict[str, tuple[str, str]] = {}  # lemma -> (clue, flag)
    completed: dict[str, dict[str, str]] = {r["lemma"]: r for r in cached}

    def task(lemma: str, row: dict[str, str], temperature: float) -> tuple[str, str, ValidationResult]:
        prompt = render_user_content(template, lemma, row["pos"], row["definition"], row["synonyms"])
        try:
            raw = ollama_chat(args.host, args.model, prompt,
                              temperature=temperature, max_tokens=MAX_TOKENS)
        except Exception as e:
            return lemma, "", ValidationResult("http-error", str(e))
        clue = clean_first_line(raw)
        target_pos = normalize_pos(row["pos"])
        return lemma, clue, validate_lemma_clue(clue, lemma, target_pos, index)

    fieldnames = ["lemma", "pos", "definition", "synonyms",
                  "lemma_clue", "attempts", "validation_flag", "rating"]

    def flush_partial(rows_dict: dict[str, dict[str, str]]) -> None:
        """Write all completed rows to the output file atomically. Called
        every FLUSH_EVERY lemmas and at the end of each round so a kill
        mid-run preserves work."""
        out_rows = sorted(rows_dict.values(), key=lambda r: r["lemma"])
        tmp = out.with_suffix(out.suffix + ".tmp")
        with tmp.open("w", encoding="utf-8", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
            writer.writeheader()
            for r in out_rows:
                writer.writerow({k: r.get(k, "") for k in fieldnames})
        tmp.replace(out)  # atomic on POSIX so readers never see partial files

    FLUSH_EVERY = 20  # rewrite after every N completed lemmas

    started = time.time()
    since_flush = 0
    for round_idx in range(MAX_ATTEMPTS):
        if not pending:
            break
        temp = ATTEMPT_TEMPERATURES[min(round_idx, len(ATTEMPT_TEMPERATURES) - 1)]
        round_lemmas = list(pending.keys())
        print(
            f"\n=== round {round_idx + 1}/{MAX_ATTEMPTS} | temp={temp} | "
            f"{len(round_lemmas)} lemmas | concurrency={args.concurrency} ===",
            file=sys.stderr,
        )
        round_started = time.time()
        with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            futures = {pool.submit(task, l, pending[l], temp): l for l in round_lemmas}
            for fut in as_completed(futures):
                lemma, clue, vr = fut.result()
                attempts = round_idx + 1
                last_output[lemma] = (clue, vr.flag)
                row_pos = pending[lemma]["pos"]
                if vr.flag == "ok":
                    completed[lemma] = {
                        **pending[lemma],
                        "lemma_clue": clue,
                        "attempts": str(attempts),
                        "validation_flag": "ok",
                        "rating": "",
                    }
                    pending.pop(lemma)
                    marker = "OK"
                    since_flush += 1
                    if since_flush >= FLUSH_EVERY:
                        flush_partial(completed)
                        since_flush = 0
                else:
                    marker = vr.flag
                print(f"  [{lemma:20s} ({row_pos:10s}) "
                      f"x{attempts}] {marker:14s} -> {clue!r}")
        # End-of-round flush so non-ok lemmas don't lose their last attempt
        # if the next round is killed mid-flight. Unconditional update so
        # attempt count advances when an earlier attempt was already flushed.
        for lemma_, row_ in pending.items():
            clue_, flag_ = last_output.get(lemma_, ("", "no-attempt"))
            if clue_:
                completed[lemma_] = {
                    **row_, "lemma_clue": clue_,
                    "attempts": str(round_idx + 1),
                    "validation_flag": flag_,
                    "rating": "",
                }
        flush_partial(completed)
        since_flush = 0
        elapsed_round = time.time() - round_started
        print(f"  -- round of {len(round_lemmas)} took {elapsed_round:.1f}s "
              f"({len(round_lemmas)/max(elapsed_round, 0.001):.1f} clue/s)", file=sys.stderr)

    # Anything still pending after the last round: keep the last attempt.
    for lemma, row in pending.items():
        clue, flag = last_output.get(lemma, ("", "no-attempt"))
        completed[lemma] = {
            **row, "lemma_clue": clue,
            "attempts": str(MAX_ATTEMPTS),
            "validation_flag": flag,
            "rating": "",
        }

    flush_partial(completed)
    out_rows = sorted(completed.values(), key=lambda r: r["lemma"])

    counts: dict[str, int] = {}
    for r in out_rows:
        counts[r["validation_flag"]] = counts.get(r["validation_flag"], 0) + 1
    elapsed = time.time() - started
    new_count = max(len(todo), 1)
    try:
        out_display = out.resolve().relative_to(repo_root)
    except ValueError:
        out_display = out
    print(f"\nWrote {len(out_rows)} rows to {out_display} in {elapsed:.1f}s "
          f"({new_count/elapsed:.1f} new clue/s)")
    for flag, n in sorted(counts.items(), key=lambda kv: -kv[1]):
        print(f"  {flag:18s} {n}")
    print("\nHand-rate the `rating` column then run:")
    print("  python scripts/eval/eval_clue_quality.py --input data/eval/lemma_clues.csv")


if __name__ == "__main__":
    main()
