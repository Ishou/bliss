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

This is a **fusion**, not a replacement. The MLX / Command-R-4bit lane
keeps running; the new Modal lane is built so it benefits from
everything both projects have already learned:

- **Corpus** — the Modal-lane SFT corpus is multi-source and
  tier-weighted. The gold pilot v1 from `bliss-clue-ai` is the highest-
  weighted tier (CC0, hand-curated, force / style-tagged); the existing
  bliss seeds (`data/curated/fr.csv`, `data/curated/short-fr.csv`, the
  hand-authored `data/lora/synthetic_clues.csv`) and the hand-rated `y`
  rows from `data/eval/lemma_clues_iter*.csv` join at lower weights so
  the model trains on the full available signal rather than 114 rows in
  isolation.
- **Validator** — `pipeline_v2` is not a verbatim port. It absorbs the
  MLX lane's hard-won failure modes (stem-leak, pleonasm — both
  documented in the `clue-ai` skill as load-bearing gates) so the
  Modal-lane outputs are validated against the union of what both
  projects have learned.
- **Outputs** — both lanes feed the same `bliss-worker
  ingest-clue-candidates` downstream path with distinct `source` tags,
  and the existing `findTopBySourcePriority` picker handles per-lemma
  source priority without changes.

## 2. What's being migrated (scope)

### 2.1 Direct ports from `bliss-clue-ai`

| Surface in `bliss-clue-ai`                          | Lands in `bliss`                                          |
|-----------------------------------------------------|-----------------------------------------------------------|
| `modal_jobs/00_hello_world.py` → `03b_finetune.py`  | `modal_jobs/` (mirrors the source layout)                 |
| `scripts/training/prepare_dataset.py`               | `scripts/clue_generation/modal/prepare_dataset.py`        |
| `scripts/pipeline/{filters,normalizers,…}.py` (style-guide-v2 validator) | `scripts/clue_generation/pipeline_v2/`        |
| `scripts/pipeline/test_negative_cases.py`           | `scripts/clue_generation/pipeline_v2/test_negative_cases.py` |
| `data/seed/gold_pilot_v1.csv` (114 hand-curated CC0 entries) | `data/curated/gold_pilot_v1.csv`                  |
| `docs/style_guide.md` (style guide v2 — 8 filters, 8 norms, 9 styles) | `docs/clue-style-guide-v2.md`            |
| `docs/pipeline_test_pilot_v1.md` (gold-pilot validation report) | `docs/eval/pipeline_v2_pilot_validation.md`    |

### 2.2 Existing bliss assets folded into the Modal-lane SFT corpus

These already live in `bliss`. The Modal-lane corpus builder (PR 4)
consumes them in addition to the gold pilot, with per-source weights so
gold remains dominant.

| Source                                              | Rows (approx.)         | Tier  | Default weight | Provenance / why                                                                                  |
|-----------------------------------------------------|-----------------------|-------|----------------|---------------------------------------------------------------------------------------------------|
| `data/curated/gold_pilot_v1.csv` (NEW, PR 2)        | 114                   | gold  | 4              | CC0 hand-curated, force-tagged, style-tagged — highest editorial quality of the lot.              |
| `data/curated/fr.csv`                               | 63                    | silver| 2              | CC0 hand-authored, short words (2-letter focus). Already the MLX-lane SFT seed.                   |
| `data/curated/short-fr.csv`                         | 239                   | silver| 2              | CC0 hand-authored short-word corpus, broader coverage than `fr.csv`.                              |
| `data/lora/synthetic_clues.csv`                     | ~400                  | silver| 2              | Claude-authored CC0 synthetic. Added in MLX iter10 and lifted acceptance +7.5pp on N=80 — earns the same silver weight as the other hand-authored sources. |
| `data/eval/lemma_clues_iter*.csv` (y-rated rows only) | ~100-300 (across 11 iters) | bronze | 1     | Human-rated `y` rows on MLX-generated clues. Multiple valid clues per lemma kept as distinct rows (variation, not noise) per the existing skill. Excludes the held-out `eval_human.jsonl` evaluation set. |

Default weights are starting points and explicit in the corpus
manifest (§3.7); tuning them is part of the pilot's learning loop, not
a hidden hyperparameter.

### 2.3 Out of scope for this migration

Deferred to follow-up workstreams, each with its own design + ADR if
needed:

- **Campaign tooling** (`scripts/campaign/*`): Google Sheets-based
  crowdsourcing of contributor clues. Large surface, paid Google API
  integration, separate sub-project.
- **Legacy CSV exports** (`data/old_dataset/*`): redundant with what's
  already in `bliss/data/eval/production/` on the MLX lane.
- **`data/external/lexique383.tsv`**: **forbidden** per ADR-0058 (Lexique3
  is CC BY-NC-SA; WordSparrow has commercial intent). Grammalecte's
  lexique-grammalecte-fr-v7.7.txt (GPL 3.0, ADR-0014) is the in-scope
  frequency/POS source; no Lexique3 import in any path.
- **Real LLM-judge (filter 8)**: mock is migrated as-is; real Anthropic/
  OpenAI call requires its own ADR per ADR-0013 (hosted-LLM lane is
  forbidden by default).
- **DPO on the Modal lane.** The pilot is SFT only. The corpus builder
  in PR 4 emits `train.jsonl` + `val.jsonl` for SFT consumption only;
  the rated y/n pairs in `data/eval/lemma_clues_iter*.csv` and the
  mined `data/lora/dpo_pairs.jsonl` will be ready for a future Modal
  DPO palier (separate workstream — DPO needs lr ≈ 1e-6, β = 0.1,
  sigmoid loss, distinct from this SFT pilot).

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
combines the 8-filter / 8-normalization style-guide-v2 surface from
`bliss-clue-ai` with the MLX lane's hard-won structural gates so the
Modal-lane outputs are validated against the union of what both
projects have learned. To keep both stable:

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

#### 3.5.1 Validator learnings fused from the MLX lane

The verbatim `bliss-clue-ai` filter set covers typography, allowed
chars, length, AI stereotypes, self-reference, French language, and
generic-label tautology. It does **not** cover two failure modes the
MLX lane has paid for in production:

| MLX flag       | What it catches                                                                                       | Source in MLX lane                                  | Port to `pipeline_v2`                                                                                  |
|----------------|-------------------------------------------------------------------------------------------------------|------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| `stem-leak`    | clue token shares ≥5-char prefix with the lemma OR is a substring of the lemma when both are ≥5 chars | `validate_clue.py::_find_stem_leak` (iter7+)         | `filter_9_stem_leak` in `pipeline_v2/filters.py`. 5-char threshold preserved (iter7 5-sample variance check is binding). |
| `pleonasm`     | closed-set patterns: `Associer ensemble`, `Monter en haut`, `Prévoir à l'avance`, etc.                | `validate_clue.py::_find_pleonasm` (PR #192 fix)     | `filter_10_pleonasm` in `pipeline_v2/filters.py`. Closed pattern set ported verbatim; widening by analogy is forbidden in both lanes. |

These two filters are added to `pipeline_v2` as part of PR 3 — not in a
follow-up — because shipping Modal-lane outputs without them would
re-introduce failure modes the MLX lane has already burned a PR cycle
on (PR #192 for pleonasm, the iter7 5-sample variance check for
stem-leak).

The filter list in the ported style-guide-v2 doc (PR 1) is amended
with §8.3 filters 9 and 10 to match; pipeline_v2's 27-case negative
test suite is extended with stem-leak and pleonasm cases drawn from
the MLX lane's known-bad examples.

Two other MLX flags (`no-head`, `unknown-head`, `head-not-lemma`,
`pos-mismatch`) depend on grammalecte morphology and the surface-form
inflation pipeline. They are **not** ported in this migration because
the Modal lane generates at lemma form only (same convention as MLX)
and the morphology layer is not in scope. They become relevant if a
future workstream pushes the Modal lane into surface-form generation.

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

### 3.7 Corpus fusion strategy

The Modal-lane SFT corpus is built by
`scripts/clue_generation/modal/build_modal_corpus.py` (PR 4) from the
five sources listed in §2.2. The build is **declarative,
deterministic, and gitignored at the output**:

- **Manifest** — `data/lora/modal_corpus_v1/manifest.toml` (committed)
  declares the per-source path, the row-selection rule (e.g. `rating
  == 'y'` for the iter CSVs), the integer `weight`, the schema-mapping
  rule, and the dataset version. Bumping any value bumps the corpus
  version (`v1` → `v2`) so each Modal run is traceable to an exact
  recipe.
- **Schema reconciliation** — sources have different columns. The
  builder maps each to the chat-Mistral target shape
  (`{messages: [{role: user, content: "Donne une définition…"},
  {role: assistant, content: <clue>}]}`). Only `mot`/`word`/`lemma` +
  `clue`/`definition`/`lemma_clue` are required; metadata (`force`,
  `pos`, `style`, `categorie`) is read where present and used for
  stratification but never leaked into the prompt — keeps prompts
  short per the iter8+ lesson recorded in the existing `clue-ai`
  skill (`Don't add features beyond what the task requires`).
- **Weighting via row replication** — the builder emits each source
  row `weight` times into the train pool before shuffling. Crude but
  reproducible, requires no SFTTrainer changes, and keeps the
  artefact human-inspectable. Default weights: gold=4, silver=2,
  bronze=1 (see §2.2 table).
- **Stratified train/val split** — split by `force` where present
  (gold pilot rows have it) with `seed=42` and val ratio 0.12 per
  the existing `prepare_dataset.py` logic; rows from sources without
  `force` are split proportionally without stratification but with
  the same seed, then merged.
- **Held-out evaluation set** — the existing MLX-lane
  `data/eval/eval_human.jsonl` (the canonical held-out lemmas) is
  **excluded** from the corpus by the builder. This keeps the
  Modal-lane evaluation comparable to MLX-lane iter rows in
  `docs/eval/clue-gen-v0.md`.
- **Output** — `data/lora/modal_corpus_v1/train.jsonl` +
  `val.jsonl` + `build_summary.md` (row counts per source / tier /
  force). The two JSONL files are gitignored (regeneratable from
  the manifest + inputs); the manifest and summary are committed.
- **Reproducibility** — `python -m scripts.clue_generation.modal.
  build_modal_corpus --manifest data/lora/modal_corpus_v1/
  manifest.toml` produces byte-identical JSONL on a given input set
  + manifest.

The weights in the manifest are a starting point. Tuning them is part
of the pilot's learning loop: PR 6's fine-tune validation report
(visual eval over 5 val examples) is the first quick read on whether
gold dominance is the right call. A future workstream can mine the
weight choice from per-source eval rates rather than picking a prior.

### 3.8 400-line PR cap

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
    ├─ fr.csv                       (existing, 63 pairs — MLX-lane SFT seed; now also a silver-tier input for Modal lane)
    ├─ short-fr.csv                 (existing, 239 pairs — silver-tier input for Modal lane)
    └─ gold_pilot_v1.csv            (NEW — 114 hand-curated entries, gold tier for Modal lane)

  data/lora/                        ← MLX-lane corpus + Modal-lane corpus manifest
    ├─ synthetic_clues.csv          (existing, ~400 Claude-authored CC0 — silver tier for Modal lane)
    ├─ dpo_pairs.jsonl              (existing, MLX DPO mining output — used by MLX, ready for future Modal DPO)
    └─ modal_corpus_v1/             (NEW)
        ├─ manifest.toml            (committed — declares sources, weights, schema mapping, seed)
        ├─ build_summary.md         (committed — row counts per source / tier / force after build)
        ├─ train.jsonl              (gitignored — built artefact)
        └─ val.jsonl                (gitignored — built artefact)

  data/eval/                        ← MLX-lane eval (existing, mostly untouched)
    ├─ lemma_clues_iter*.csv        (11 files — y-rated rows feed Modal-lane bronze tier)
    ├─ eval_human.jsonl             (existing held-out set — EXCLUDED from Modal-lane corpus to keep evals comparable)
    └─ …

  scripts/clue_generation/          ← MLX lane (existing, untouched)
    ├─ generate_clues_lora_batched.py
    ├─ run_production.sh
    └─ … (mlx-lm SFT/DPO configs, filter trainers, etc.)

  scripts/clue_generation/modal/    ← NEW — Modal lane glue
    ├─ build_modal_corpus.py        (multi-source → train.jsonl + val.jsonl per manifest)
    ├─ prepare_dataset.py           (legacy single-source path, kept for the gold-only smoke run)
    └─ export_adapter_to_csv.py     (Modal adapter → local CSV in clue_candidates shape)

  scripts/clue_generation/pipeline_v2/  ← NEW — fused style-guide-v2 + MLX-lane validator
    ├─ filters.py                   (filter_1_typographiques … filter_8_llm_juge_mock from bliss-clue-ai
    │                                + filter_9_stem_leak, filter_10_pleonasm ported from validate_clue.py)
    ├─ normalizers.py               (norm_1 … norm_8)
    ├─ run_pipeline.py              (CLI orchestrator)
    └─ test_negative_cases.py       (27 cases from bliss-clue-ai + stem-leak/pleonasm cases from MLX lane)

  modal_jobs/                       ← NEW — Modal-side training paliers
    ├─ 00_hello_world.py            (palier 0 — Modal infra smoke)
    ├─ 01_gpu_check.py              (palier 1 — A100 detection)
    ├─ 02_download_mistral.py       (palier 2 — Mistral Nemo → mots-fleches-models volume)
    ├─ 03a_upload_dataset.py        (palier 3a — train/val JSONL → mots-fleches-datasets volume)
    └─ 03b_finetune.py              (palier 3b — QLoRA SFT, A100-40GB, adapters → mots-fleches-adapters volume)

  scripts/eval/                     ← existing MLX-lane validator
    └─ validate_clue.py             (UNCHANGED — guards committed CSV via runtime test)

  docs/clue-style-guide-v2.md       ← NEW — style guide v2 (amended with filters 9 + 10 vs the bliss-clue-ai source)
  docs/eval/pipeline_v2_pilot_validation.md ← NEW — gold-pilot validation report
  docs/adr/0057-cloud-gpu-modal-finetune-lane.md ← NEW — accept-gate ADR

  .claude/skills/clue-ai/SKILL.md   ← UPDATED to describe both lanes + fused corpus + fused validator
```

### Data flow (Modal lane, post-fusion)

```
  data/curated/gold_pilot_v1.csv ────────┐  (weight 4, gold)
  data/curated/fr.csv ───────────────────┤  (weight 2, silver)
  data/curated/short-fr.csv ─────────────┤  (weight 2, silver)
  data/lora/synthetic_clues.csv ─────────┤  (weight 1, silver)
  data/eval/lemma_clues_iter*.csv (y) ───┘  (weight 1, bronze)
                       │
                       ▼
  scripts/clue_generation/modal/build_modal_corpus.py
                       │   reads data/lora/modal_corpus_v1/manifest.toml
                       │   schema-maps each source → chat-Mistral format
                       │   replicates rows by weight
                       │   stratified split by `force` where present, seed=42, val=0.12
                       │   excludes data/eval/eval_human.jsonl held-out lemmas
                       ▼
  data/lora/modal_corpus_v1/{train,val}.jsonl     (gitignored, regeneratable)
                       │
                       ▼
  modal_jobs/03a_upload_dataset.py
                       │   → Modal volume `mots-fleches-datasets`
                       ▼
  modal_jobs/03b_finetune.py                      (Mistral Nemo from `mots-fleches-models` volume — palier 2)
                       │   → adapter on Modal volume `mots-fleches-adapters`
                       ▼
  scripts/clue_generation/modal/export_adapter_to_csv.py
                       │   (Modal-side inference over target lemma list →
                       │    pipeline_v2 validation/normalization (incl. stem-leak + pleonasm) →
                       │    shape into clue_candidates CSV columns)
                       ▼
  lemma_clues_mistral-nemo-pilot-vN.csv           (uncommitted)
                       │
                       ▼
  bliss-worker ingest-clue-candidates --source mistral-nemo-pilot-vN
                       │
                       ▼
  Postgres clue_candidates
                       │
                       ▼
  bliss-worker export-words                       (findTopBySourcePriority — picks per lemma)
                       │
                       ▼
  grid/api/src/main/resources/words/words-fr.csv  (committed; same downstream as MLX lane)
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
~280 lines.

Port `bliss-clue-ai/docs/style_guide.md` with these amendments:

- Frontmatter pointer to ADR-0057 for the Mistral-Nemo target context.
- Cross-reference to `.claude/skills/clue-ai/SKILL.md` for the MLX
  lane.
- §8.3 (filters) amended to include **filter 9 — interdiction
  stem-leak** and **filter 10 — interdiction pleonasm**, per §3.5.1
  of this design. Brief rationale + the exact thresholds (stem-leak ≥
  5 chars; pleonasm closed pattern set) are inlined; the deeper
  background lives in the `clue-ai` skill (`validate_clue.py` flags
  table).

`NOTICE.md` gets a `lingua-language-detector` entry (Apache 2.0).

### PR 2 — Gold pilot v1 seed corpus (data)

Branch: `chore/clue-ai-modal-gold-pilot-v1`.
Files: `data/curated/gold_pilot_v1.csv` (114 rows, CC0, ≤200 lines
including header).

Single-file data drop. CSV schema matches the bliss-clue-ai source
(`mot;definition;pos;categorie;style;force;longueur;source;meta`) so
`prepare_dataset.py` in PR 4 reads it without modification.

### PR 3 — pipeline_v2 validator, fused (chore)

Branch: `chore/clue-ai-modal-pipeline-v2`.
Files (new module under `scripts/clue_generation/pipeline_v2/`):

- `__init__.py` (~5 lines)
- `filters.py` — filters 1-7 from `bliss-clue-ai` (typography, allowed
  chars, length, AI-stereotypes, self-reference, French language via
  lingua, tautology) **plus** filter 9 (stem-leak) and filter 10
  (pleonasm) ported from `validate_clue.py` per §3.5.1 (~320 lines)
- `normalizers.py` — 8 normalizations in fixed order (~120 lines)
- `llm_judge_mock.py` — filter 8 mock (~40 lines)
- `run_pipeline.py` — CLI orchestrator (preview + `--apply`) (~150 lines)
- `test_negative_cases.py` — extended regression suite: 27 cases from
  `bliss-clue-ai` + ≥4 stem-leak cases + ≥6 pleonasm cases from the
  MLX-lane known-bad set documented in the `clue-ai` skill (~330 lines)
- `requirements.txt` (pinned: `lingua-language-detector==2.2.0`,
  nothing else) (~3 lines)

Total is well above the cap, so this PR will split into **PR 3a**
(filters 1-8 + normalizers + tests for the bliss-clue-ai surface) and
**PR 3b** (filters 9 + 10 + extended negative test cases + CLI). The
split is meaningful: PR 3a stands on its own as the verbatim port; PR
3b is the fusion. Decide final boundary at plan-writing time per
[[feedback-standing-cap-override]].

### PR 4 — Modal-lane corpus builder + manifest (chore)

Branch: `chore/clue-ai-modal-build-corpus`.
Files:

- `scripts/clue_generation/modal/__init__.py`
- `scripts/clue_generation/modal/build_modal_corpus.py` (~250 lines —
  declarative multi-source corpus builder per §3.7: reads manifest,
  schema-maps each source, replicates by weight, stratifies, excludes
  held-out lemmas, writes train/val JSONL + build_summary.md)
- `scripts/clue_generation/modal/prepare_dataset.py` (~170 lines —
  ported from `bliss-clue-ai/scripts/training/prepare_dataset.py`,
  kept as the single-source gold-only smoke path for quick iteration)
- `data/lora/modal_corpus_v1/manifest.toml` (committed — declares
  sources + weights + schema mapping; ~50 lines)
- `scripts/clue_generation/modal/test_build_modal_corpus.py` (~200
  lines — schema-mapping per source, weight-replication math,
  stratification reproducibility with seed=42, eval_human.jsonl
  exclusion, byte-identical rebuild)
- `scripts/clue_generation/modal/test_prepare_dataset.py` (~80 lines —
  the simpler single-source path)
- `.gitignore` entries for `data/lora/modal_corpus_v1/{train,val}.jsonl`
  and `data/seed/gold_pilot_v1_{train,val}.jsonl`.

Total is ~750+ lines — well above the cap. Will split into **PR 4a**
(`prepare_dataset.py` + manifest skeleton + single-source tests) and
**PR 4b** (`build_modal_corpus.py` + multi-source tests). 4a unblocks
the gold-only smoke run on Modal; 4b adds the full fused corpus.
Decide final boundary at plan-writing time.

Depends on PR 2 (gold_pilot_v1.csv). The bliss-side sources
(`data/curated/fr.csv`, `data/curated/short-fr.csv`,
`data/lora/synthetic_clues.csv`, `data/eval/lemma_clues_iter*.csv`)
already exist in main and are read in place.

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
| `pipeline_v2/filters` 1–8 | Unit tests on each filter; the 27-case negative regression suite                   | `pytest scripts/clue_generation/pipeline_v2/`        |
| `pipeline_v2/filters` 9–10 (fused from MLX) | Unit tests on stem-leak threshold (5-char boundary, both directions) and on each pleonasm closed-set pattern (matches and non-matches) | same             |
| `pipeline_v2/normalizers` | NFC, apostrophe, capitalisation, quote stripping unit tests                       | same                             |
| `build_modal_corpus.py` | Per-source schema mapping; weight-replication arithmetic (`row * weight` exact count); stratification reproducible with `seed=42`; `data/eval/eval_human.jsonl` lemmas are absent from output; byte-identical rebuild on same manifest + inputs | `pytest scripts/clue_generation/modal/`              |
| `prepare_dataset.py`   | Stratification by `force` is reproducible with `seed=42`; train + val partition is disjoint and exhaustive | same                             |
| `export_adapter_to_csv.py` | CSV shape (column names, UTF-8, ordering) + `source` / `model_version` tagging   | unit tests with Modal call mocked at the boundary    |
| Modal palier 0         | `modal run` returns "hello"; secret resolution; image build                            | one-shot, ~$0.01                 |
| Modal palier 1         | `nvidia-smi` reports A100-40GB                                                         | one-shot, ~$0.02                 |
| Modal palier 2         | Idempotent: second run reports `skipped`; volume size ≤ 24 GB                          | one-shot, ~$0.05                 |
| Modal palier 3a        | JSONL files reach `/datasets`; line counts match local                                 | one-shot, ~$0.01                 |
| Modal palier 3b        | Pilot run completes; adapter persists on volume; visual eval on 5 val examples         | one-shot, ~$1.50                 |
| End-to-end             | `export_adapter_to_csv.py` produces a CSV that `bliss-worker ingest-clue-candidates` accepts without errors | manual, after PR 7 lands         |

Property-based tests (per CLAUDE.md §"Engineering rules with
operational bite") cover the CSV codec in pipeline_v2, the
stratification reproducibility in `prepare_dataset.py`, and the
manifest-driven byte-identical rebuild in `build_modal_corpus.py`.

## 8. Risks and mitigations

| Risk                                                          | Mitigation                                                                                                       |
|---------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| Modal cost runaway during a buggy run                         | Per-function `timeout=` hard cap (already in source); PR 6 body quotes the cap for each palier.                  |
| Mistral HF licence accept missed (download fails)             | PR 5 runbook front-loads the licence-accept step; first-run failure is loud and fast.                            |
| Modal API outage blocks training                              | MLX lane keeps working; production CSV unchanged; no production dependency on Modal.                             |
| Two-lane confusion in `clue_candidates` source priority       | Distinct `source` namespaces (`command-r-lora-vN-iterMM` vs `mistral-nemo-pilot-vN`); `findTopBySourcePriority` already keys on `source`. |
| pipeline_v2 calibration drifts from `validate_clue.py`        | Filters 9 (stem-leak) and 10 (pleonasm) are ported verbatim from `validate_clue.py` per §3.5.1, so the two validators agree on those two failure modes by construction. Other edge cases are documented in PR 3b's body as known divergences pending a consolidation study. |
| Cross-source schema confusion in `build_modal_corpus.py`      | Per-source schema mapping is declared in the manifest, not hard-coded, and `test_build_modal_corpus.py` asserts the chat-Mistral output shape for every declared source. Bumping a source's columns bumps the manifest's version. |
| Wrong corpus weights ship a Mistral-Nemo adapter biased away from gold | Defaults err high on gold (4× replication). PR 6's visual eval on 5 val rows is the first read; if the model regresses, the next iteration drops weights via a `modal_corpus_v2/manifest.toml` rather than retraining off the same bias. |
| `data/eval/eval_human.jsonl` leak into the Modal-lane corpus  | Builder enforces exclusion as an assertion, not a comment; `test_build_modal_corpus.py` asserts that no held-out lemma appears in `train.jsonl` or `val.jsonl`. |
| Branch overlap with parallel survey orchestrator              | Disjoint branch prefix + disjoint file surface + disjoint ADR number (0057 vs 0056). Verified in §3.6.           |
| Cap override sustained pushback on PR 6                       | Pre-flag at dispatch per [[feedback-cap-override-short-circuit]]; PR body cites the standing override grant and the single-file rationale. |

## 9. What this design intentionally does **not** decide

- **Final corpus weights**. Defaults (gold=4, silver=2, bronze=1) are
  starting points declared in the v1 manifest. Tuning is part of the
  pilot's learning loop; a v2 manifest is the right shape for any
  revision.
- **DPO on the Modal lane.** The pilot is SFT only. The y-rated rows
  ingested by the corpus builder + the existing
  `data/lora/dpo_pairs.jsonl` are ready for a future Modal DPO palier
  (lr ≈ 1e-6, β = 0.1) but that's a separate workstream with its own
  ADR amendment (DPO has different convergence behaviour and silently
  destroys an SFT base if misconfigured per the existing skill).
- **Which adapter version becomes "production" on the Modal lane.** PR
  6 is a pilot; promotion is a follow-up workstream once iter-on-iter
  comparison is meaningful (200+ lemmas, N=3 candidates, best-of-3
  per the MLX-lane variance methodology).
- **Whether `pipeline_v2/` eventually subsumes `validate_clue.py`.**
  Even after the fusion of filters 9 + 10, `validate_clue.py` still
  contains the `head-not-lemma`, `pos-mismatch`, and grammalecte-
  morphology-dependent flags that pipeline_v2 doesn't. Consolidation
  is a separate workstream with its own ADR.
- **Whether to migrate the campaign / Google-Sheets tooling.**
  Separate sub-project; ADR class because of the paid Google API
  surface.
- **Whether to enable the real LLM-judge (filter 8).** ADR-class per
  ADR-0013's hosted-LLM lane retirement.

## 10. Definition of done

- ADR-0057 is `Accepted`.
- All PRs in §5 have landed on `main` (9 logical PRs; PR 3 and PR 4
  each split for cap, so 11 PRs in practice).
- `data/lora/modal_corpus_v1/manifest.toml` is committed and
  `build_modal_corpus.py` produces a byte-identical JSONL pair from
  it. `build_summary.md` shows row counts per source / tier / force.
- `pipeline_v2`'s extended negative test suite includes the stem-leak
  and pleonasm cases from the MLX lane; `pytest scripts/
  clue_generation/pipeline_v2/` is green.
- `modal_jobs/03b_finetune.py` has been run end-to-end at least once
  on the fused corpus, and the adapter exists on the
  `mots-fleches-adapters` volume.
- `scripts/clue_generation/modal/export_adapter_to_csv.py` has been
  run end-to-end at least once and produced a `lemma_clues_…csv` that
  `bliss-worker ingest-clue-candidates` accepts.
- `.claude/skills/clue-ai/SKILL.md` documents both lanes, the fused
  corpus + manifest, and the fused validator, and an agent following
  the skill can run either pipeline end-to-end from the runbook alone.
