"""Modal adapter to clue_candidates CSV bridge (see ADR-0057, spec section 4)."""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
DEFAULT_OUT_DIR = ROOT / "data" / "modal_exports"

CSV_COLS = ["lemma", "clue_text", "source", "model_version", "confidence"]


def _generate_clues_on_modal(
    lemmas: list[str],
    adapter_path: str,
    *,
    temperatures: tuple[float, ...] = (0.3, 0.5, 0.7, 0.9, 1.1),
    k_per_lemma: int = 5,
) -> list[dict[str, Any]]:
    # Boundary to Modal-side inference; stub until palier 4 lands.
    # Returns one dict per (lemma, candidate) with keys lemma, clue, confidence.
    # Modal app + function will live in modal_jobs/04_infer.py.
    raise NotImplementedError(
        "Wire to Modal inference function in a follow-up palier 4."
    )


def _validate_clue(clue: str, lemma: str) -> dict[str, Any]:
    # Run pipeline_v2 against a single (clue, lemma) pair.
    from ..pipeline_v2 import run_pipeline as rp
    row = {
        "mot": lemma,
        "definition": clue,
        "pos": "nom_commun",
        "categorie": "autre",
        "style": "definition_directe",
        "force": "1",
        "longueur": str(len(clue.split())),
        "source": "modal-inference",
        "meta": "",
    }
    out = rp.traiter_ligne(row)
    return {
        "status": out["pipeline_status"],
        "flag": out.get("_traces", {}),
    }


def run(
    *,
    lemma_list: list[str],
    adapter_path: str,
    model_version: str,
    source_tag: str,
    out_path: Path,
) -> dict[str, int]:
    # Generate, validate, write CSV. Returns {accepted, dropped}.
    raw = _generate_clues_on_modal(lemma_list, adapter_path)
    by_lemma: dict[str, list[dict[str, Any]]] = {}
    for cand in raw:
        by_lemma.setdefault(cand["lemma"], []).append(cand)
    accepted: list[dict[str, Any]] = []
    dropped = 0
    for lemma, cands in by_lemma.items():
        # Best-of-K: sort by confidence descending, pick first that passes.
        cands_sorted = sorted(cands, key=lambda c: -float(c["confidence"]))
        chosen = None
        for c in cands_sorted:
            verdict = _validate_clue(c["clue"], lemma)
            if verdict["status"] in ("accept", "accept_with_warning"):
                chosen = c
                break
        if chosen is None:
            dropped += 1
            continue
        accepted.append({
            "lemma": lemma,
            "clue_text": chosen["clue"],
            "source": source_tag,
            "model_version": model_version,
            "confidence": f"{float(chosen['confidence']):.4f}",
        })

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=CSV_COLS, lineterminator="\n")
        w.writeheader()
        for row in accepted:
            w.writerow(row)

    return {"accepted": len(accepted), "dropped": dropped}


def _read_lemma_list(path: Path) -> list[str]:
    # One lemma per line; strip empties and lines starting with #.
    with path.open(encoding="utf-8") as f:
        return [
            line.strip() for line in f
            if line.strip() and not line.startswith("#")
        ]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--adapter-path", required=True,
        help="Modal volume path, e.g. /adapters/mistral-nemo-pilot-v1",
    )
    parser.add_argument(
        "--model-version", required=True,
        help="Records to clue_candidates.model_version",
    )
    parser.add_argument(
        "--source-tag", required=True,
        help="Records to clue_candidates.source",
    )
    parser.add_argument(
        "--lemma-list", type=Path, required=True,
        help="Text file with one lemma per line",
    )
    parser.add_argument(
        "--out", type=Path, default=None,
        help="Output CSV path; defaults to data/modal_exports/<source-tag>.csv",
    )
    args = parser.parse_args(argv)

    out_path = args.out or DEFAULT_OUT_DIR / f"{args.source_tag}.csv"
    lemmas = _read_lemma_list(args.lemma_list)
    summary = run(
        lemma_list=lemmas,
        adapter_path=args.adapter_path,
        model_version=args.model_version,
        source_tag=args.source_tag,
        out_path=out_path,
    )
    print(f"Exported: accepted={summary['accepted']}  dropped={summary['dropped']}")
    print(f"CSV: {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
