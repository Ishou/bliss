#!/usr/bin/env python3
"""Route rows from `data/eval/runtime_feedback.csv` to the right consumer.

Reads the append-only feedback journal and materialises four output files:

  data/eval/lemma_clues_runtime.csv          model-training rows (rating y/b/n)
  data/eval/blocklist_clues.csv              hard filter for validate_clue
  data/eval/inflation_bugs.csv               regression list for inflater work
  data/eval/runtime_feedback_unrouted.csv    autre / unknown reason — manual triage

The runtime_feedback.csv is the single source of truth (and the future user-
rating UI submission target — same schema, just `source = 'user'`). All
output files are derived; the script is idempotent.

Routing taxonomy:

  | reason             | route                                           |
  |--------------------|-------------------------------------------------|
  | mauvais_sens       | runtime (rating=n)                              |
  | trop_obscur        | runtime (rating=n)                              |
  | trop_facile        | runtime (rating=b)                              |
  | inapproprié        | runtime (rating=n) AND blocklist                |
  | faute_de_français  | surface == lemma → runtime (n)                  |
  |                    | surface != lemma → inflation_bugs               |
  | autre / unknown    | unrouted (manual review)                        |

Idempotency notes:
- runtime / inflation / unrouted are full overwrites — the feedback file is
  the source of truth.
- blocklist is additive: pre-existing rows are preserved and unioned with
  new entries (deduped on the lowercased clue text). This protects manual
  blocklist entries that didn't originate in runtime_feedback.

Usage:

  ./triage_runtime_feedback.py \
      --feedback data/eval/runtime_feedback.csv \
      --out-dir  data/eval/

"""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from pathlib import Path

# Reasons whose semantics are "this lemma clue is wrong" — bad meaning,
# unsolvable, or too direct. Each maps to a fixed rating in the runtime
# corpus row that downstream training picks up.
_RUNTIME_RATING_BY_REASON: dict[str, str] = {
    "mauvais_sens": "n",
    "trop_obscur": "n",
    "trop_facile": "b",
}

# Output column schemas. Picked to be a superset of `lemma_clues_iter*.csv`
# so build_corpus.py can glob runtime rows alongside iter rows once it's
# taught the new path. `source_reason` and `notes` are extra columns the
# iter CSVs don't have; csv.DictReader tolerates extras.
_RUNTIME_FIELDS = [
    "lemma", "pos", "definition", "synonyms",
    "lemma_clue", "attempts", "validation_flag", "rating",
    "source_reason", "notes",
]
_BLOCKLIST_FIELDS = ["clue", "reason", "added_at"]
_INFLATION_FIELDS = ["surface", "lemma", "pos", "clue", "reason", "notes"]
_UNROUTED_FIELDS = [
    "surface", "lemma", "pos", "clue", "reason", "severity",
    "rater_id", "found_at", "source_iter", "grid_id", "notes",
]


@dataclass(frozen=True)
class RouteDecision:
    """Per-row routing decision. Multiple targets can fire (inapproprié
    writes to both runtime and blocklist); unrouted is exclusive."""
    runtime_rating: str | None = None
    blocklist: bool = False
    inflation: bool = False
    unrouted: bool = False


def route_row(reason: str, surface_eq_lemma: bool) -> RouteDecision:
    """Pure routing function. Surface-vs-lemma comparison is pre-computed
    by the caller so the routing logic stays decoupled from the morphology
    index — easy to test, easy to reuse from a future API submission path."""
    if reason in _RUNTIME_RATING_BY_REASON:
        return RouteDecision(runtime_rating=_RUNTIME_RATING_BY_REASON[reason])
    if reason == "inapproprié":
        return RouteDecision(runtime_rating="n", blocklist=True)
    if reason == "faute_de_français":
        if surface_eq_lemma:
            return RouteDecision(runtime_rating="n")
        return RouteDecision(inflation=True)
    return RouteDecision(unrouted=True)


def _surface_eq_lemma(row: dict[str, str]) -> bool:
    surface = (row.get("surface") or "").strip().lower()
    lemma = (row.get("lemma") or "").strip().lower()
    if not surface or not lemma:
        return False
    return surface == lemma


def _runtime_row(fb: dict[str, str], rating: str) -> dict[str, str]:
    return {
        "lemma": fb.get("lemma", ""),
        "pos": fb.get("pos", ""),
        "definition": "",
        "synonyms": "",
        "lemma_clue": fb.get("clue", ""),
        "attempts": "",
        "validation_flag": "",
        "rating": rating,
        "source_reason": fb.get("reason", ""),
        "notes": fb.get("notes", ""),
    }


def _inflation_row(fb: dict[str, str]) -> dict[str, str]:
    return {k: fb.get(k, "") for k in _INFLATION_FIELDS}


def _unrouted_row(fb: dict[str, str]) -> dict[str, str]:
    return {k: fb.get(k, "") for k in _UNROUTED_FIELDS}


def _load_existing_blocklist(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


def _write_csv(path: Path, fields: list[str], rows: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=fields)
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, "") for k in fields})


def triage(feedback_path: Path, out_dir: Path) -> dict[str, int]:
    """Route every row in `feedback_path` to its output file(s).

    Returns a count dict for caller logging. Output paths are fixed names
    inside `out_dir` so the validator and downstream consumers don't need
    to be re-pointed every run."""
    feedback_path = Path(feedback_path)
    out_dir = Path(out_dir)

    runtime: list[dict[str, str]] = []
    inflation: list[dict[str, str]] = []
    unrouted: list[dict[str, str]] = []
    new_blocklist_entries: list[tuple[str, str, str]] = []  # (clue, reason, added_at)

    with feedback_path.open(encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            decision = route_row(row.get("reason", ""), _surface_eq_lemma(row))
            if decision.runtime_rating is not None:
                runtime.append(_runtime_row(row, decision.runtime_rating))
            if decision.blocklist:
                clue = (row.get("clue") or "").strip()
                if clue:
                    new_blocklist_entries.append((
                        clue, row.get("reason", ""), row.get("found_at", ""),
                    ))
            if decision.inflation:
                inflation.append(_inflation_row(row))
            if decision.unrouted:
                unrouted.append(_unrouted_row(row))

    # Blocklist is union(existing, new). Dedup by lowercased clue — match
    # the same normalisation validate_clue.load_blocklist uses, so the
    # writer and reader agree on identity.
    blocklist_path = out_dir / "blocklist_clues.csv"
    existing = _load_existing_blocklist(blocklist_path)
    seen_keys: set[str] = set()
    blocklist_rows: list[dict[str, str]] = []
    for r in existing:
        clue = (r.get("clue") or "").strip()
        if not clue:
            continue
        key = clue.lower()
        if key in seen_keys:
            continue
        seen_keys.add(key)
        blocklist_rows.append({
            "clue": clue,
            "reason": r.get("reason", ""),
            "added_at": r.get("added_at", ""),
        })
    for clue, reason, added_at in new_blocklist_entries:
        key = clue.lower()
        if key in seen_keys:
            continue
        seen_keys.add(key)
        blocklist_rows.append({"clue": clue, "reason": reason, "added_at": added_at})

    _write_csv(out_dir / "lemma_clues_runtime.csv", _RUNTIME_FIELDS, runtime)
    _write_csv(blocklist_path, _BLOCKLIST_FIELDS, blocklist_rows)
    _write_csv(out_dir / "inflation_bugs.csv", _INFLATION_FIELDS, inflation)
    _write_csv(out_dir / "runtime_feedback_unrouted.csv", _UNROUTED_FIELDS, unrouted)

    return {
        "runtime": len(runtime),
        "blocklist": len(blocklist_rows),
        "inflation": len(inflation),
        "unrouted": len(unrouted),
    }


def _main() -> None:
    repo = Path(__file__).resolve().parents[2]
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--feedback", type=Path,
                    default=repo / "data" / "eval" / "runtime_feedback.csv")
    ap.add_argument("--out-dir", type=Path,
                    default=repo / "data" / "eval")
    args = ap.parse_args()
    counts = triage(args.feedback, args.out_dir)
    print(f"triaged {sum(counts.values())} rows: {counts}")


if __name__ == "__main__":
    _main()
