"""Palier 5 — RAFT round-N corpus build + finetune handoff. See docs/runbooks/clue-loop.md."""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# Repo root on sys.path so ``scripts.*`` resolves under direct invocation.
sys.path.insert(0, str(ROOT))

from scripts.clue_generation.modal.build_modal_corpus import build_corpus  # noqa: E402

MANIFEST_DEFAULT = ROOT / "data" / "lora" / "modal_corpus_v1" / "manifest.toml"
CORPUS_DIR = ROOT / "data" / "lora" / "modal_corpus_v1"


def snapshot_manifest(src: Path, round_n: int) -> Path:
    """Copy the live manifest to a round-tagged file so each round is reproducible."""
    dst = src.with_name(f"manifest.raft-round-{round_n}.toml")
    shutil.copy2(src, dst)
    return dst


def main(argv: list[str]) -> int:
    """Build round-N corpus, snapshot manifest, print handoff commands."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--round", type=int, required=True,
                        help="Round number (1+). Round-0 uses 03b directly.")
    parser.add_argument("--manifest", type=Path, default=MANIFEST_DEFAULT,
                        help="Live manifest to read + snapshot.")
    parser.add_argument("--manifest-snapshot", type=Path, default=None,
                        help="Override snapshot path. Default: alongside manifest.")
    args = parser.parse_args(argv)

    run_tag = f"raft-round-{args.round}"

    if not args.manifest.exists():
        print(f"ERROR: manifest not found at {args.manifest}", file=sys.stderr)
        return 1

    snapshot = args.manifest_snapshot or snapshot_manifest(args.manifest, args.round)
    if args.manifest_snapshot:
        shutil.copy2(args.manifest, snapshot)
    print(f"[snapshot] manifest -> {snapshot.relative_to(ROOT)}")

    summary = build_corpus(ROOT, args.manifest, CORPUS_DIR)
    print(f"[corpus]   train={summary['train_size']} val={summary['val_size']} "
          f"weighted_total={summary['total_with_weights']}")

    print()
    print(f"Next steps for {run_tag}:")
    print("  1) modal run modal_jobs/03a_upload_dataset.py  # uploads fused corpus")
    print("  2) modal run modal_jobs/03b_finetune.py        # adapter -> "
          "/adapters/mistral-nemo-pilot-v1")
    print("  3) modal volume cp mots-fleches-adapters/mistral-nemo-pilot-v1 \\")
    print(f"       mots-fleches-adapters/{run_tag}  # tag the round adapter")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
