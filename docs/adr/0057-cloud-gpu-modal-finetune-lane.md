# ADR-0057: Cloud-GPU clue-AI fine-tuning lane via Modal + HuggingFace

## Status
Proposed

## Context
The current clue-AI pipeline trains a LoRA adapter on
`mlx-community/c4ai-command-r-08-2024-4bit` locally on Apple Silicon
(ADR-0013). It's production-shipping (iter17+, `docs/eval/clue-gen-v0.md`)
but locked to one base model and one hardware platform. We want to
A/B-test a second base model (Mistral Nemo 12B, Apache 2.0) and
benefit from cloud GPU time for larger experiments, without
disturbing the MLX lane.

Modal is the cheapest path to A100-40GB GPU time without standing up
our own k8s GPU pool. HuggingFace Hub hosts the Mistral Nemo weights
behind a one-time licence accept.

## Decision
Introduce a **second training lane** at `modal_jobs/` +
`scripts/clue_generation/modal/` + `scripts/clue_generation/pipeline_v2/`.
The lane is **training-only**: inference for production stays local +
committed CSV per ADR-0013 §8. Both lanes feed the same
`bliss-worker ingest-clue-candidates` with distinct `source` tags
(`command-r-lora-vN-iterMM` vs `mistral-nemo-pilot-vN`); the
existing `findTopBySourcePriority` picker handles per-lemma source
priority without changes.

Modal is the first paid third-party in the repo. The cost surface is
bounded by a per-function `timeout=` (1800 s palier 2, 3600 s palier
3b) and an explicit per-palier cost annotation in each script's
docstring. A pilot end-to-end run costs ≈$1.50.

HuggingFace's gated download requires a one-time manual licence accept
for Mistral-Nemo-Base-2407 on the HF Hub UI, with the same account
whose `HF_TOKEN` is uploaded to Modal as `modal secret create
huggingface HF_TOKEN=hf_…`. The secret never leaves Modal containers.

The Modal-lane SFT corpus is multi-source and tier-weighted per the
design spec §3.7: `data/curated/gold_pilot_v1.csv` (gold, 4×),
`data/curated/fr.csv` + `short-fr.csv` + `data/lora/synthetic_clues.csv`
(silver, 2×), and y-rated rows of `data/eval/lemma_clues_iter*.csv`
(bronze, 1×). `data/eval/eval_human.jsonl` is explicitly excluded so
Modal-lane evaluation stays comparable to the MLX iter logbook.

The `pipeline_v2` validator absorbs `validate_clue.py`'s `stem-leak`
(5-char threshold) and `pleonasm` (closed pattern set) gates from
the MLX lane in addition to the 8 style-guide-v2 filters ported from
`bliss-clue-ai`. The runtime guard `test_runtime_csv_pleonasms.py`
continues to use `validate_clue.py` against the committed
`words-fr.csv` — both validators coexist.

## Consequences

Easier:
- A/B tests across base models without disturbing the MLX adapter
  ladder.
- Cloud GPU time for larger experiments (200+ lemmas, N=3 candidates,
  best-of-3 — the methodological floor for promoting a structural
  change per the `clue-ai` skill).
- Future Modal DPO palier has ready-to-consume y/n preference data
  via the existing rated iter CSVs + `data/lora/dpo_pairs.jsonl`.

Harder:
- Two `model_version` namespaces in `clue_candidates`; promotion
  rules must be explicit per lane.
- Monthly Modal bill (variable, capped per-run by `timeout=`).
- Mistral licence accept is a one-time manual step; first-run failure
  is loud and fast.
- Two validators on different lanes — they agree on stem-leak and
  pleonasm by construction (the ported gates) but may diverge on
  edge cases until a future consolidation workstream.

## Threat model — Modal + HF secret surface

- **`HF_TOKEN`** lives only as a Modal secret named `huggingface`.
  Never on disk in the repo. `secret-scan` (gitleaks) does not see it.
  Rotation: `modal secret create huggingface HF_TOKEN=hf_new --force`,
  then re-run palier 2 to verify.
- **Modal API key** is per-developer; lives in `~/.modal.toml` outside
  the repo. CI does not run Modal jobs (paliers are manual).
- **Mistral Nemo weights** are gated; the licence accept is a one-time
  HF Hub action on the same account whose token is in `huggingface`.

## Alternatives considered

- **Standing up GPU pool on Hetzner k3s** (extending ADR-0009): more
  ops surface, monthly fixed cost regardless of utilisation. Rejected
  for a pilot.
- **Reusing the MLX lane with a different base model**: mlx-lm
  doesn't support Mistral Nemo at 4-bit on Apple Silicon today.
- **Continuing on local CPU/CUDA dev box**: no A100-class hardware
  available; iteration time would be ~50× slower.
