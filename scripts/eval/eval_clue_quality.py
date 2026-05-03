#!/usr/bin/env python3
"""Score the hand-rated clue results and update docs/eval/clue-gen-v0.md.

Reads data/eval/run_v0_results.csv (or --input). Each row's `rating` column
should be one of:
    y / yes / ✓ / 1     => acceptable (1 point)
    b / borderline / ◯  => borderline (0.5 points)
    n / no / ✗ / 0      => unacceptable (0 points)
    (blank)             => unrated (skipped, reported separately)

Computes acceptance = sum(score) / N, where N excludes unrated rows.
Applies the plan's decision rule:
    >= 85%  -> SHIP base model, skip Phase 4
    70-85%  -> RUN Phase 4 (LoRA fine-tune)
    < 70%   -> INVESTIGATE before committing

Writes the result block (with timestamp + per-bucket breakdown + decision)
into docs/eval/clue-gen-v0.md, replacing any prior auto-generated block
between the markers `<!-- AUTO:BEGIN -->` and `<!-- AUTO:END -->`.
"""

import argparse
import csv
import datetime as dt
import re
import sys
from collections import defaultdict
from pathlib import Path

ACCEPT = {"y", "yes", "✓", "1", "ok"}
BORDERLINE = {"b", "borderline", "◯", "0.5", "maybe"}
REJECT = {"n", "no", "✗", "x", "0", "ko"}

MARKER_BEGIN = "<!-- AUTO:BEGIN -->"
MARKER_END = "<!-- AUTO:END -->"


def score(rating: str) -> float | None:
    r = rating.strip().lower()
    if r in ACCEPT:
        return 1.0
    if r in BORDERLINE:
        return 0.5
    if r in REJECT:
        return 0.0
    return None


def decision(acceptance: float) -> tuple[str, str]:
    if acceptance >= 0.85:
        return ("SHIP", "Acceptance ≥ 85%. Skip Phase 4. Ship base model + prompts as the offline batch tool.")
    if acceptance >= 0.70:
        return ("FINE-TUNE", "70% ≤ acceptance < 85%. Run Phase 4 (LoRA fine-tune); fine-tuning likely moves the needle to ≥ 90%.")
    return ("INVESTIGATE", "Acceptance < 70%. Enrich prompt, test alternative bases, grow curated set, then re-run Phase 0.")


def render_block(rows: list[dict[str, str]]) -> str:
    by_length: dict[int, list[float]] = defaultdict(list)
    by_pos: dict[str, list[float]] = defaultdict(list)
    scored: list[float] = []
    unrated = 0

    for row in rows:
        s = score(row.get("rating", ""))
        if s is None:
            unrated += 1
            continue
        scored.append(s)
        try:
            by_length[int(row["length"])].append(s)
        except (KeyError, ValueError):
            pass
        pos = (row.get("pos") or "(unknown)").strip() or "(unknown)"
        by_pos[pos].append(s)

    n = len(scored)
    if n == 0:
        return f"{MARKER_BEGIN}\nNo rows rated yet.\n{MARKER_END}"

    acceptance = sum(scored) / n
    label, rationale = decision(acceptance)
    ts = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")

    out = [
        MARKER_BEGIN,
        f"_Last evaluated: {ts}_",
        "",
        f"**Acceptance: {acceptance:.1%}** ({n} rated, {unrated} unrated)",
        "",
        f"**Decision: {label}** — {rationale}",
        "",
        "### Breakdown by length",
        "",
        "| Length | N | Acceptance |",
        "| ---: | ---: | ---: |",
    ]
    for length in sorted(by_length):
        scores = by_length[length]
        out.append(f"| {length} | {len(scores)} | {sum(scores)/len(scores):.1%} |")

    out.extend(["", "### Breakdown by POS", "", "| POS | N | Acceptance |", "| --- | ---: | ---: |"])
    for pos in sorted(by_pos):
        scores = by_pos[pos]
        out.append(f"| {pos} | {len(scores)} | {sum(scores)/len(scores):.1%} |")

    out.append(MARKER_END)
    return "\n".join(out)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=None, help="path to results CSV")
    parser.add_argument("--output", type=Path, default=None, help="path to eval markdown")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent.parent
    src = args.input or repo_root / "data" / "eval" / "run_v0_results.csv"
    out = args.output or repo_root / "docs" / "eval" / "clue-gen-v0.md"

    if not src.exists():
        print(f"missing {src} - run generate_clues_v0.py first", file=sys.stderr)
        sys.exit(1)

    with src.open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))

    block = render_block(rows)

    if out.exists():
        text = out.read_text(encoding="utf-8")
        if MARKER_BEGIN in text and MARKER_END in text:
            new_text = re.sub(
                rf"{re.escape(MARKER_BEGIN)}.*?{re.escape(MARKER_END)}",
                block,
                text,
                count=1,
                flags=re.DOTALL,
            )
        else:
            new_text = text.rstrip() + "\n\n" + block + "\n"
    else:
        new_text = block + "\n"

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(new_text, encoding="utf-8")
    print(f"Wrote {out.relative_to(repo_root)}")


if __name__ == "__main__":
    main()
