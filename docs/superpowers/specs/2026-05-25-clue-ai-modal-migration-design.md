# Migrate the Modal + HuggingFace cloud-GPU clue-AI training lane from `bliss-clue-ai` into `bliss`

Spec date: 2026-05-25.
Author: orchestrator (autonomous mode).
Status: draft, awaiting maintainer review.

## 1. Goal

Bring the Modal-based cloud-GPU fine-tuning pipeline currently living in
the sibling repo `../bliss-clue-ai` into the main `bliss` repo as a
**second training lane** alongside the existing Apple-Silicon MLX lane
under `scripts/clue_generation/`. The migration is **operational, not
exploratory** — `bliss-clue-ai` is a single-commit scratch tree with all
the interesting code untracked. The intent is to make the work
permanent, reviewed, and reproducible inside `bliss`.

The migration explicitly does **not** replace the existing MLX /
Command-R-4bit lane. Both lanes will coexist and feed the same
`bliss-worker ingest-clue-candidates` downstream path with distinct
`source` tags.

## 2. What's being migrated (scope)

In scope:

| Surface in `bliss-clue-ai`                          | Lands in `bliss`                                          |
|-----------------------------------------------------|-----------------------------------------------------------|
| `modal_jobs/00_hello_world.py` → `03b_finetune.py`  | `modal_jobs/` (mirrors the source layout)                 |
| `scripts/training/prepare_dataset.py`               | `scripts/clue_generation/modal/prepare_dataset.py`        |
| `scripts/pipeline/{filters,normalizers,…}.py` (style-guide-v2 validator) | `scripts/clue_generation/pipeline_v2/`        |
| `scripts/pipeline/test_negative_cases.py`           | `scripts/clue_generation/pipeline_v2/test_negative_cases.py` |
| `data/seed/gold_pilot_v1.csv` (114 hand-curated CC0 entries) | `data/curated/gold_pilot_v1.csv`                  |
| `docs/style_guide.md` (style guide v2 — 8 filters, 8 norms, 9 styles) | `docs/clue-style-guide-v2.md`            |
| `docs/pipeline_test_pilot_v1.md` (gold-pilot validation report) | `docs/eval/pipeline_v2_pilot_validation.md`    |

Out of scope for this migration (deferred to follow-up workstreams,
each with its own design + ADR if needed):

- **Campaign tooling** (`scripts/campaign/*`): Google Sheets-based
  crowdsourcing of contributor clues. Large surface, paid Google API
  integration, separate sub-project.
- **Legacy CSV exports** (`data/old_dataset/*`): redundant with what's
  already in `bliss/data/eval/production/` on the MLX lane.
- **`data/external/lexique383.tsv`**: licence review needed (CC BY-NC-SA
  3.0); defer until we know whether we actually need it in addition to
  the grammalecte lexique already in tree.
- **Real LLM-judge (filter 8)**: mock is migrated as-is; real Anthropic/
  OpenAI call requires its own ADR per ADR-0013 (hosted-LLM lane is
  forbidden by default).

## 3. Why this is non-trivial — binding constraints

### 3.1 New paid third-party service (Modal) — ADR required

CLAUDE.md "Things to never do without explicit approval" lists
"Introduce a paid third-party service." Modal is a paid cloud-GPU
provider (A100-40GB at $3.10/hr per the source `03b_finetune.py`
docstring). This migration is therefore **blocked on a new ADR** that
the maintainer must accept before any code lands.

Proposed: **ADR-0057 — Cloud-GPU fine-tuning lane via Modal +
HuggingFace** (next free number after the in-flight survey ADR-0056 on
the parallel orchestrator's branch).

### 3.2 ADR-0013 §8 compatibility

ADR-0013 §8 reads "The committed CSV is the production source of truth.
No deployed inference service." The Modal lane is **training only** —
inference still runs locally (batch script over the committed adapter,
then export to `lemma_clues_<source>.csv`, then `bliss-worker
ingest-clue-candidates`). ADR-0013 §8 holds; ADR-0057 will state this
explicitly.

### 3.3 New base model + new tech stack

The Modal lane uses a fundamentally different stack from the MLX lane.
Both must keep working independently.

| Concern        | MLX lane (existing)                              | Modal lane (new)                                            |
|----------------|--------------------------------------------------|-------------------------------------------------------------|
| Hardware       | local Apple Silicon (M-series)                   | cloud GPU (Modal — A100-40GB)                               |
| Base model     | `mlx-community/c4ai-command-r-08-2024-4bit` (32B, CC-BY-NC) | `mistralai/Mistral-Nemo-Base-2407` (12B, Apache 2.0)|
| Quantization   | MLX 4-bit                                        | bnb NF4 + double-quant + bf16 compute                       |
| Trainer        | `mlx_lm.lora`                                    | `trl.SFTTrainer` (HuggingFace TRL + PEFT + bitsandbytes)    |
| Attention      | MLX-native                                       | Flash Attention 2 (with SDPA fallback)                      |
| Adapter format | MLX adapters                                     | PEFT LoRA `adapter_model.safetensors`                       |
| Adapters location | local `models/lora-clue-vN/`                  | Modal volume `mots-fleches-adapters` (no local snapshot; the lane is volume-resident) |
| Inference      | `mlx_lm.batch_generate` locally                  | `transformers.generate` invoked on a Modal function that re-mounts `mots-fleches-adapters` (production inference is run on Modal, results CSV is downloaded locally) |

Adapters from one lane are not interchangeable with the other — they
target different base models. Each lane keeps its own `model_version`
tag in `clue_candidates` (e.g. `command-r-lora-vN-iterMM` vs
`mistral-nemo-pilot-vN`). The existing `findTopBySourcePriority` picker
already supports per-lemma source priority and does the right thing.

### 3.4 HuggingFace gated download + secret management

Mistral Nemo Base 2407 is licence-gated on HuggingFace Hub. The Modal
`02_download_mistral.py` palier uses a Modal secret `huggingface`
containing `HF_TOKEN`. Provisioning steps (one-time, manual):

1. Accept the Mistral Nemo licence on the HF Hub UI with the same
   account whose token will be uploaded to Modal.
2. `modal secret create huggingface HF_TOKEN=hf_…` — the secret is
   never committed; only its **name** appears in code.
3. The secret is consumed only inside Modal containers via
   `modal.Secret.from_name("huggingface")`. `secret-scan` (gitleaks)
   doesn't see it because it's never on disk in the repo.

### 3.5 Two-lane validation pipeline — coexistence with `validate_clue.py`

The existing `scripts/eval/validate_clue.py` is the structural gate for
the MLX lane and the load-bearing runtime guard for the committed
`words-fr.csv` (via `test_runtime_csv_pleonasms.py`). It must not break
during this migration.

The new `pipeline_v2/` is the validator for **Modal-lane outputs**. It
is **independently more comprehensive** (8 filters + 8 normalizations,
NFD/NFC handling, lingua FR/EN detection, mock LLM-judge structural
gate) but is also calibrated on a different gold set and uses different
flag names. To keep both stable:

- `pipeline_v2/` is a brand-new module. It does not import from, modify,
  or replace `validate_clue.py`.
- The runtime guard `test_runtime_csv_pleonasms.py` continues to use
  `validate_clue.py` against the committed CSV.
- A Modal-lane output goes through `pipeline_v2/` before being shaped
  into `lemma_clues_<source>.csv` for the `bliss-worker ingest` path.
- A future workstream (out of scope here) can study whether
  `pipeline_v2/` can subsume `validate_clue.py` and which gates must be
  back-ported to keep the MLX lane consistent. That study and its ADR
  are deferred.

### 3.6 Parallel orchestrator coordination

Per maintainer's instruction: "there's another orchestrator running in
parallel so be sure not to overlap branches." That orchestrator is
working the **survey module** (open PR #616
`feat/survey-application`, plus `docs/survey-module-orchestration` and
`backup-feat-survey-app` branches).

This migration uses a clearly disjoint namespace:

| Resource           | Survey orchestrator                 | Modal-lane migration                  |
|--------------------|-------------------------------------|---------------------------------------|
| Branch prefix      | `feat/survey-*`, `docs/survey-*`    | `feat/clue-ai-modal-*`, `chore/clue-ai-modal-*`, `docs/clue-ai-modal-*` |
| Files touched      | `survey/**`, `docs/adr/0056-*`      | `modal_jobs/**`, `scripts/clue_generation/modal/**`, `scripts/clue_generation/pipeline_v2/**`, `data/curated/gold_pilot_v1.csv`, `docs/clue-style-guide-v2.md`, `docs/eval/pipeline_v2_*`, `docs/adr/0057-*`, `.claude/skills/clue-ai/SKILL.md` |
| ADR number         | 0056 (in flight on their branch)    | 0057                                  |
| `clue_candidates.source` tag | n/a                       | `mistral-nemo-pilot-vN` (new namespace) |

No file collision is expected. The one shared surface is
`.claude/skills/clue-ai/SKILL.md` — the survey orchestrator is not
touching it (their work is in `survey/`), and PR 8 of this migration
will edit it.

### 3.7 400-line PR cap

The work decomposes cleanly into 9 PRs (§5). PR 6 (palier 3b fine-tune
script) is ~400 lines because the source is monolithic; if it
crosses the cap, invoke the standing cap override per
[[feedback-standing-cap-override]] (single workstream, single file,
splitting hurts review more than it helps).

## 4. Architecture

```
┌───────────────────────────────────────────────────────────────────────┐
│                       bliss main repo, after migration                 │
└───────────────────────────────────────────────────────────────────────┘

  data/curated/                     ← seed corpus (CC0, committed)
    ├─ fr.csv                       (existing, 62 pairs)
    └─ gold_pilot_v1.csv            (NEW — 114 hand-curated entries)

  scripts/clue_generation/          ← MLX lane (existing, untouched)
    ├─ generate_clues_lora_batched.py
    ├─ run_production.sh
    └─ … (mlx-lm SFT/DPO configs, filter trainers, etc.)

  scripts/clue_generation/modal/    ← NEW — Modal lane glue
    ├─ prepare_dataset.py           (gold_pilot_v1.csv → train.jsonl + val.jsonl)
    └─ export_adapter_to_csv.py     (Modal adapter → local CSV in clue_candidates shape)

  scripts/clue_generation/pipeline_v2/  ← NEW — style-guide-v2 validator
    ├─ filters.py                   (filter_1_typographiques … filter_7_tautologie)
    ├─ normalizers.py               (norm_1 … norm_8)
    ├─ llm_judge_mock.py            (filter 8 — structural gate, no API call)
    ├─ run_pipeline.py              (CLI orchestrator)
    └─ test_negative_cases.py       (27-case regression suite)

  modal_jobs/                       ← NEW — Modal-side training paliers
    ├─ 00_hello_world.py            (palier 0 — Modal infra smoke)
    ├─ 01_gpu_check.py              (palier 1 — A100 detection)
    ├─ 02_download_mistral.py       (palier 2 — Mistral Nemo → mots-fleches-models volume)
    ├─ 03a_upload_dataset.py        (palier 3a — train/val JSONL → mots-fleches-datasets volume)
    └─ 03b_finetune.py              (palier 3b — QLoRA SFT, A100-40GB, adapters → mots-fleches-adapters volume)

  scripts/eval/                     ← existing MLX-lane validator
    └─ validate_clue.py             (UNCHANGED — guards committed CSV via runtime test)

  docs/clue-style-guide-v2.md       ← NEW — style guide v2 (Mistral-Nemo target)
  docs/eval/pipeline_v2_pilot_validation.md ← NEW — gold-pilot validation report
  docs/adr/0057-cloud-gpu-modal-finetune-lane.md ← NEW — accept-gate ADR

  .claude/skills/clue-ai/SKILL.md   ← UPDATED to describe both lanes
```

### Data flow (Modal lane)

```
  data/curated/gold_pilot_v1.csv
            │
            ▼
  scripts/clue_generation/modal/prepare_dataset.py
            │   (stratified split by force, seed=42, val ratio 0.12)
            ▼
  data/seed/gold_pilot_v1_{train,val}.jsonl       (chat-Mistral format, gitignored)
            │
            ▼
  modal_jobs/03a_upload_dataset.py
            │   → Modal volume `mots-fleches-datasets` (/datasets)
            ▼
  modal_jobs/03b_finetune.py                       (consumes Mistral Nemo from `mots-fleches-models` volume populated by palier 2)
            │   → adapter on Modal volume `mots-fleches-adapters`
            ▼
  scripts/clue_generation/modal/export_adapter_to_csv.py
            │   (Modal inference over target lemma list →
            │    pipeline_v2 validation/normalization →
            │    shape into clue_candidates CSV columns)
            ▼
  lemma_clues_mistral-nemo-pilot-vN.csv            (uncommitted)
            │
            ▼
  bliss-worker ingest-clue-candidates --source mistral-nemo-pilot-vN
            │
            ▼
  Postgres clue_candidates
            │
            ▼
  bliss-worker export-words                        (findTopBySourcePriority — picks per lemma)
            │
            ▼
  grid/api/src/main/resources/words/words-fr.csv   (committed; same downstream as MLX lane)
```

## 5. Decomposition into PRs

Each PR is sized to the 400-line cap; the order mirrors the
schema-first / ADR-first workflow per ADR-0001 §7.

### PR 0 — ADR-0057 (docs)

Branch: `docs/adr-0057-modal-clue-finetune-lane`.
Files: `docs/adr/0057-cloud-gpu-modal-finetune-lane.md` (single new
ADR, ~150 lines).

Content:

- **Context**: existing MLX lane is iter17+ shipping; we want a second
  base model to A/B against (Mistral Nemo 12B vs Command-R 32B) and
  the MLX lane can't easily run multi-base experiments. Modal +
  HuggingFace is the cheapest path to A100 GPU time.
- **Decision**: introduce Modal as a paid third-party service for
  **training only**. Inference, including the production read path,
  remains local + committed CSV per ADR-0013 §8.
- **Consequences**: monthly bill bounded by `timeout=` per function
  + an explicit per-PR cost annotation in `03b_finetune.py`'s
  docstring. HF gated download requires a one-time manual licence
  accept. Two parallel lanes mean two `model_version` namespaces in
  `clue_candidates`. The dbnary-synonym lane and the
  source-priority picker continue to work unchanged.
- **Threat model section** for the secret-management surface
  (`HF_TOKEN` as a Modal secret, not in repo; Modal API key as a
  developer-only secret).

**Approval gate.** No code PR lands until ADR-0057 merges.

### PR 1 — Style guide v2 (docs)

Branch: `docs/clue-style-guide-v2`.
Files: `docs/clue-style-guide-v2.md` + `NOTICE.md` patch
(lingua-language-detector entry).
~250 lines.

Port `bliss-clue-ai/docs/style_guide.md` verbatim with two edits:

- Frontmatter pointer to ADR-0057 for the Mistral-Nemo target context.
- Cross-reference to the legacy `.claude/skills/clue-ai/SKILL.md`
  pipeline for the MLX lane.

`NOTICE.md` gets a `lingua-language-detector` entry (Apache 2.0).

### PR 2 — Gold pilot v1 seed corpus (data)

Branch: `chore/clue-ai-modal-gold-pilot-v1`.
Files: `data/curated/gold_pilot_v1.csv` (114 rows, CC0, ≤200 lines
including header).

Single-file data drop. CSV schema matches the bliss-clue-ai source
(`mot;definition;pos;categorie;style;force;longueur;source;meta`) so
`prepare_dataset.py` in PR 4 reads it without modification.

### PR 3 — pipeline_v2 validator (chore)

Branch: `chore/clue-ai-modal-pipeline-v2`.
Files (new module under `scripts/clue_generation/pipeline_v2/`):

- `__init__.py` (~5 lines)
- `filters.py` — filters 1-7 (typography, allowed chars, length,
  AI-stereotypes, self-reference, French language via lingua,
  tautology) (~250 lines)
- `normalizers.py` — 8 normalizations in fixed order (~120 lines)
- `llm_judge_mock.py` — filter 8 mock (~40 lines)
- `run_pipeline.py` — CLI orchestrator (preview + `--apply`) (~150 lines)
- `test_negative_cases.py` — 27-case regression suite (~250 lines)
- `requirements.txt` (pinned: `lingua-language-detector==2.2.0`,
  nothing else) (~3 lines)

The total is well above 400 lines, so this PR will likely be split into
two: **PR 3a** (filters + normalizers + tests, no CLI) and **PR 3b**
(`run_pipeline.py` + `test_negative_cases.py` integration). Decide at
plan-writing time per [[feedback-standing-cap-override]].

### PR 4 — Modal-lane dataset prep (chore)

Branch: `chore/clue-ai-modal-prepare-dataset`.
Files:

- `scripts/clue_generation/modal/__init__.py`
- `scripts/clue_generation/modal/prepare_dataset.py` (~170 lines —
  ported from `bliss-clue-ai/scripts/training/prepare_dataset.py`)
- `scripts/clue_generation/modal/test_prepare_dataset.py`
  (stratification + reproducibility tests) (~100 lines)
- `.gitignore` entry for `data/seed/gold_pilot_v1_{train,val}.jsonl`
  (the generated artefacts; only the CSV input is committed).

Depends on PR 2 (gold_pilot_v1.csv).

### PR 5 — Modal paliers 0–2 (chore)

Branch: `chore/clue-ai-modal-paliers-0-1-2`.
Files:

- `modal_jobs/__init__.py` (empty marker — makes the directory a
  proper Python package; mirrors `bliss-clue-ai` source layout)
- `modal_jobs/README.md` — runbook: install Modal CLI, create
  `huggingface` secret, accept Mistral licence on HF, `modal run …`
  for each palier in order. Includes expected cost per palier and the
  rollback story (`modal app stop`). (~100 lines)
- `modal_jobs/00_hello_world.py` (~25 lines)
- `modal_jobs/01_gpu_check.py` (~60 lines)
- `modal_jobs/02_download_mistral.py` (~250 lines, ported as-is)

Total ~435 lines including README — cap override likely needed; alternative
is to defer the README to PR 8. Decide at plan-writing time.

Paliers 0–2 cost ~$0.05/run total — they're cheap smoke tests.

### PR 6 — Modal paliers 3a + 3b (chore)

Branch: `chore/clue-ai-modal-paliers-3a-3b`.
Files:

- `modal_jobs/03a_upload_dataset.py` (~145 lines)
- `modal_jobs/03b_finetune.py` (~615 lines)

Both ported verbatim from `bliss-clue-ai`. PR will be ~760 lines —
**cap override invoked** per [[feedback-standing-cap-override]]: the
fine-tune script is a single workstream and splitting it would force
the reader to context-switch across mid-function boundaries. PR
description names the override and the per-file rationale.

Depends on PR 5 (paliers 0–2 prove the Modal infra) and PR 4 (dataset
JSONL).

A single end-to-end pilot run is part of PR 6's test plan (expected
cost: ~$1.50 per run; documented in the PR body).

### PR 7 — Modal → CSV bridge (chore)

Branch: `chore/clue-ai-modal-export-bridge`.
Files:

- `scripts/clue_generation/modal/export_adapter_to_csv.py` (~250
  lines): downloads adapter from Modal volume (or re-mounts on a
  Modal inference function), runs `transformers.generate` over a
  target lemma list (re-using the surface list from
  `grid/api/src/main/resources/words/words-fr.csv`), passes each
  generated clue through `pipeline_v2/run_pipeline.py`, writes
  `lemma_clues_mistral-nemo-pilot-vN.csv` in the shape that
  `bliss-worker ingest-clue-candidates` expects (columns:
  `lemma, clue_text, source, model_version, confidence`).
- `scripts/clue_generation/modal/test_export_adapter_to_csv.py` —
  unit tests over the CSV-shaping logic (the Modal-side
  inference call is mocked at the boundary per "Mock only at
  external boundaries" — Modal is genuinely external).
  (~150 lines)

Depends on PR 3 (pipeline_v2) and PR 6 (adapter exists).

### PR 8 — Skill + onboarding update (docs)

Branch: `docs/clue-ai-modal-skill-update`.
Files:

- `.claude/skills/clue-ai/SKILL.md` — major edit. Add a new
  "Cloud-GPU lane (Mistral Nemo + Modal)" section after the current
  "Pipeline at a glance" diagram, with: when to use which lane,
  cost guards, runbook pointers, the new `model_version` namespace.
  ~120 lines added.
- `docs/eval/pipeline_v2_pilot_validation.md` — port of
  `bliss-clue-ai/docs/pipeline_test_pilot_v1.md`, kept as the
  reference logbook entry for the gold-pilot calibration. ~280 lines.

## 6. Branch & PR governance

To stay disjoint from the parallel survey orchestrator:

- All branches under `feat/clue-ai-modal-*`, `chore/clue-ai-modal-*`,
  or `docs/clue-ai-modal-*` / `docs/adr-0057-…`.
- All PR titles start with a conventional scope `(grid-…)` is wrong
  here — these don't touch a JVM context. Per the commitlint memory
  [[feedback-commitlint-gotchas]], the scope is a free-text
  short-string and `clue-ai-modal` is valid. So:
  - `docs(adr): add ADR-0057 Modal cloud-GPU clue-AI lane`
  - `chore(clue-ai-modal): port style-guide-v2 pipeline filters and normalizers`
  - `chore(clue-ai-modal): port Modal palier 0–2 (download Mistral Nemo)`
- PRs land in order PR 0 → PR 1, 2 (parallel) → PR 3 → PR 4 → PR 5 →
  PR 6 → PR 7 → PR 8. Stacked-PR rebase recipe from
  [[feedback-stacked-pr-rebase]] applies after each squash-merge.

## 7. Testing strategy

| Surface                | Test                                                                                  | Where it runs                    |
|------------------------|---------------------------------------------------------------------------------------|----------------------------------|
| `pipeline_v2/filters`  | Unit tests on each filter; the 27-case `test_negative_cases.py` regression suite      | `pytest scripts/clue_generation/pipeline_v2/`        |
| `pipeline_v2/normalizers` | NFC, apostrophe, capitalisation, quote stripping unit tests                       | same                             |
| `prepare_dataset.py`   | Stratification by `force` is reproducible with `seed=42`; train + val partition is disjoint and exhaustive | `pytest scripts/clue_generation/modal/`              |
| `export_adapter_to_csv.py` | CSV shape (column names, UTF-8, ordering) + `source` / `model_version` tagging   | unit tests with Modal call mocked at the boundary    |
| Modal palier 0         | `modal run` returns "hello"; secret resolution; image build                            | one-shot, ~$0.01                 |
| Modal palier 1         | `nvidia-smi` reports A100-40GB                                                         | one-shot, ~$0.02                 |
| Modal palier 2         | Idempotent: second run reports `skipped`; volume size ≤ 24 GB                          | one-shot, ~$0.05                 |
| Modal palier 3a        | JSONL files reach `/datasets`; line counts match local                                 | one-shot, ~$0.01                 |
| Modal palier 3b        | Pilot run completes; adapter persists on volume; visual eval on 5 val examples         | one-shot, ~$1.50                 |
| End-to-end             | `export_adapter_to_csv.py` produces a CSV that `bliss-worker ingest-clue-candidates` accepts without errors | manual, after PR 7 lands         |

Property-based tests (per CLAUDE.md §"Engineering rules with
operational bite") cover the CSV codec in pipeline_v2 and the
stratification reproducibility in `prepare_dataset.py`.

## 8. Risks and mitigations

| Risk                                                          | Mitigation                                                                                                       |
|---------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| Modal cost runaway during a buggy run                         | Per-function `timeout=` hard cap (already in source); PR 6 body quotes the cap for each palier.                  |
| Mistral HF licence accept missed (download fails)             | PR 5 runbook front-loads the licence-accept step; first-run failure is loud and fast.                            |
| Modal API outage blocks training                              | MLX lane keeps working; production CSV unchanged; no production dependency on Modal.                             |
| Two-lane confusion in `clue_candidates` source priority       | Distinct `source` namespaces (`command-r-lora-vN-iterMM` vs `mistral-nemo-pilot-vN`); `findTopBySourcePriority` already keys on `source`. |
| pipeline_v2 calibration drifts from `validate_clue.py`        | Documented in PR 3 README that the two validators serve different lanes and are not expected to agree on edge cases until a follow-up study consolidates them. |
| Branch overlap with parallel survey orchestrator              | Disjoint branch prefix + disjoint file surface + disjoint ADR number (0057 vs 0056). Verified in §3.6.           |
| Cap override sustained pushback on PR 6                       | Pre-flag at dispatch per [[feedback-cap-override-short-circuit]]; PR body cites the standing override grant and the single-file rationale. |

## 9. What this design intentionally does **not** decide

- Which adapter version becomes "production" on the Modal lane. PR 6
  is a pilot; promotion is a follow-up workstream once iter-on-iter
  comparison is meaningful.
- Whether `pipeline_v2/` eventually subsumes `validate_clue.py`. That
  is a separate consolidation workstream with its own ADR.
- Whether to migrate the campaign / Google-Sheets tooling. Separate
  sub-project; ADR class because of the paid Google API surface.
- Whether to enable the real LLM-judge (filter 8). ADR-class per
  ADR-0013's hosted-LLM lane retirement.

## 10. Definition of done

- ADR-0057 is `Accepted`.
- All 9 PRs (or 10 if PR 3 splits) have landed on `main`.
- `modal_jobs/03b_finetune.py` has been run end-to-end at least once
  and the adapter exists on the `mots-fleches-adapters` volume.
- `scripts/clue_generation/modal/export_adapter_to_csv.py` has been
  run end-to-end at least once and produced a `lemma_clues_…csv` that
  `bliss-worker ingest-clue-candidates` accepts.
- `.claude/skills/clue-ai/SKILL.md` documents both lanes and the
  agent following the skill can run either pipeline end-to-end from
  the runbook alone.
