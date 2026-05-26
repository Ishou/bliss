---
name: clue-ai
description: Implement, train, evaluate, or fix the fully-local French clue-generation AI pipeline. Stack is mlx-lm (LoRA + DPO on command-r-08-2024-4bit) + sentence-transformers (CamemBERT semantic filter) + grammalecte morphology + DBnary SPARQL/TTL + a Python `validate_clue` gate, fronted by the Kotlin `bliss-worker` (`clue_candidates` Postgres table + CSV export). No hosted-LLM lane — generation is on-device only. Use when the task touches `scripts/clue_generation/`, `scripts/eval/`, `scripts/dbnary/`, `data/{eval,lora,lora_filter,lora_dpo,dbnary,curated}/`, `models/`, `grid/worker/src/main/kotlin/com/bliss/grid/worker/{clues,dbnary}/`, the Python validator/morphology helpers, the eval logbook at `docs/eval/clue-gen-v0.md`, when an iter LoRA/DPO config under `scripts/clue_generation/lora_iter*.yaml` needs revising, or when changing what gets emitted into `grid/api/src/main/resources/words/words-fr.csv`. Encodes ADR-0013 (offline batch worker), ADR-0023 (DBnary CC BY-SA constraints), ADR-0024 (synonym-lemma narrow relaxation), the eval methodology from `docs/eval/clue-gen-v0.md`, and the licence + leak failure modes that have actually bitten this repo.
paths: ["scripts/clue_generation/**", "scripts/eval/**", "scripts/dbnary/**", "grid/worker/src/main/kotlin/com/bliss/grid/worker/clues/**", "grid/worker/src/main/kotlin/com/bliss/grid/worker/dbnary/**", "grid/application/src/main/kotlin/com/bliss/grid/application/lexicon/**", "grid/domain/src/main/kotlin/com/bliss/grid/domain/lexicon/**", "data/eval/**", "data/lora/**", "data/lora_filter/**", "data/lora_dpo/**", "data/dbnary/**", "data/curated/**", "models/**", "docs/eval/**"]
---

# Clue-AI playbook

For everything in the offline French clue-generation pipeline: corpus building, LoRA training, DPO refinement, CamemBERT filter scoring, validator gates, DBnary handling, and the bridge into `bliss-worker` that lands clues in `clue_candidates` and exports `words-fr.csv`. The pipeline is local-dev only (no deployed inference): the production read path is the in-tree CSV. Everything below is binding because licence missteps and leak regressions have already cost real iterations.

## Anchor documents

- `docs/adr/0013-words-clues-worker.md` — the offline-batch shape, the §5 clue rules (length cap, retry-on-overrun, drop on repeated overrun), the worker subcommand surface, and the §8 amendment that made the committed CSV the production source of truth (no DB read path in prod). The hosted-LLM lane described in §5 has been retired in favour of pure-LoRA generation; only the local Python pipeline produces clues today.
- `docs/adr/0023-dbnary-lexical-data-source.md` — the CC BY-SA constraints. Read constraint #1 and #2 word-for-word before touching anything that surfaces DBnary content (definition_text, synonyms, gloss).
- `docs/adr/0024-dbnary-synonym-lemma-as-direct-clue-candidate.md` — the **narrow** relaxation that authorises a capitalized `synonym_lemma` as `clue_text` with `source = 'dbnary-synonym'`. Definitions are still off-limits. Don't extend the relaxation by analogy.
- `docs/eval/clue-gen-v0.md` — the live eval logbook. Iter1 → iter17+ (and counting) with acceptance numbers, failure-mode taxonomy, variance-at-N analysis, and the ranked next-step list. **This logbook is the source of truth for which LoRA adapter and which filter are currently production.** Read the latest iter row before invoking the pipeline; the `run_production.sh` defaults below drift behind. Append a new iter section here for any change you ship; do not silently overwrite an iter's row.
- `NOTICE.md` — attribution surface. The Hunspell-fr / DBnary entries here are load-bearing for licence compliance; don't drop one without an ADR.

## Pipeline at a glance

```
DBnary TTL ─► scripts/dbnary/parse_ttl_to_csv.py ─► data/dbnary/dbnary_fr.csv
                                                          │
                                                          ▼
words-fr.csv (lemma list) ────────► sample top-X per length, length-interleaved
                                                          │
                                                          ▼
                          ┌───────── scripts/clue_generation/generate_clues_lora_batched.py
                          │           (mlx-lm + LoRA adapter, K=5 candidates @ T∈{0.3,0.5,0.7,0.9,1.1})
                          │                              │
                          │                              ▼
                          │           scripts/eval/validate_clue.py  (structural gates)
                          │                              │
                          ▼                              ▼
              scripts/eval/synonym_clues.py     CamemBERT filter (models/filter-camembert-vN; latest in eval logbook)
              (DBnary synonym → capitalized clue)         │
                          │                              ▼
                          ▼                    threshold split (T≈0.65) → lemma_clues_shipped.csv
                          │                              │
                          │                              ▼
                          │                    build_surface_clues.py (per-surface inflation:
                          │                      MorphologyIndex.inflect on the head token,
                          │                      POS precedence nom>adj>adv>verbe)
                          │                              │
                          │                              ▼
                          │                    merge_clues_into_wordlist.py
                          └─────────► CSV(s) merged ────► bliss-worker ingest-clue-candidates
                                                                          │
                                                                          ▼
                                                                  Postgres clue_candidates
                                                                          │
                                                                          ▼
                                              bliss-worker export-words ──► words-fr.csv (committed)
```

The two ingestion lanes (LoRA-generated + dbnary-synonym) live side-by-side in `clue_candidates` with distinct `source` values, and `export-words`'s `findTopBySourcePriority` picks per lemma. Don't collapse them — the priority order is how we keep dbnary-synonym as a free-win without it pre-empting better LLM clues.

## Two-lane reality (post-ADR-0057)

The pipeline above is the **MLX lane** (Apple Silicon, Command-R-4bit,
mlx-lm). A second **Modal lane** lives at `modal_jobs/` +
`scripts/clue_generation/modal/` + `scripts/clue_generation/pipeline_v2/`,
trained on Mistral-Nemo-Base-2407 on Modal A100s (ADR-0057). Both
lanes feed the same `bliss-worker ingest-clue-candidates` with
distinct `source` tags:

| Lane  | Base model                      | Hardware           | Validator                                                          | Source tag                       |
|-------|---------------------------------|--------------------|---------------------------------------------------------------------|----------------------------------|
| MLX   | Command-R-08-2024-4bit (32B)    | Apple Silicon      | `scripts/eval/validate_clue.py` (also runtime guard on committed CSV) | `command-r-lora-vN-iterMM`       |
| Modal | Mistral-Nemo-Base-2407 (12B)    | Modal A100-40GB    | `scripts/clue_generation/pipeline_v2/` (fused: 8 ported + filters 9 + 10 from validate_clue.py) | `mistral-nemo-pilot-vN`          |

`findTopBySourcePriority` in the worker picks per lemma — no
collision.

### When to use which lane

- **MLX lane:** quick iteration on a known adapter ladder (iter17+),
  the production-shipping path today, regression-checked nightly
  by `test_runtime_csv_pleonasms.py`.
- **Modal lane:** A/B against Mistral Nemo, larger experiments
  (200+ lemmas, N=3 candidates), cloud GPU access.

### Modal-lane runbook (cost-aware)

| # | Command                                                                          | Cost ≈ | Validates |
|---|----------------------------------------------------------------------------------|--------|-----------|
| 0 | `modal run modal_jobs/00_hello_world.py`                                         | $0.01  | Modal auth |
| 1 | `modal run modal_jobs/01_gpu_check.py`                                           | $0.02  | A100 available |
| 2 | `modal run modal_jobs/02_download_mistral.py`                                    | $0.05  | HF token + licence + volume |
| C | `python3 -m scripts.clue_generation.modal.build_modal_corpus`                    | $0     | Fused-corpus JSONL |
| 3a | `modal run modal_jobs/03a_upload_dataset.py`                                    | $0.01  | Dataset volume |
| 3b | `modal run modal_jobs/03b_finetune.py`                                          | $1.50  | Adapter on volume |
| 7 ⚠ | `python3 -m scripts.clue_generation.modal.export_adapter_to_csv …`             | $0.20  | **Not yet runnable** — `_generate_clues_on_modal` is a `NotImplementedError` stub; requires palier 4 Modal inference app (follow-up ADR). Do not attempt until that ADR lands. |
| 8 | `bliss-worker ingest-clue-candidates --source mistral-nemo-pilot-vN`             | $0     | Postgres `clue_candidates` |
| 9 | `bliss-worker export-words`                                                      | $0     | Updates committed `words-fr.csv` |

### Modal-lane corpus

- Manifest: `data/lora/modal_corpus_v1/manifest.toml` (committed).
- Tier weights (default): gold=4, silver=2, bronze=1. See spec
  `docs/superpowers/specs/2026-05-25-clue-ai-modal-migration-design.md`
  §2.2 + §3.7.
- Held-out enforcement: `data/eval/eval_human.jsonl` lemmas are
  excluded by assertion in `build_modal_corpus.py`. Same held-out
  set as MLX lane → eval numbers comparable.
- Bumping any manifest value bumps the corpus version
  (`modal_corpus_v1` → `modal_corpus_v2`). Every Modal adapter
  records the manifest hash in its `model_version` so adapter →
  corpus → manifest is traceable.

### Don'ts (Modal lane)

- **Don't** point `03b_finetune.py` at gold-only data without
  invoking `--mode gold-only` on `03a_upload_dataset.py` — the
  default fused corpus is what the spec calls for.
- **Don't** bump the Mistral base model without a new ADR and
  retraining: adapters are base-model-specific.
- **Don't** lower the pleonasm or stem-leak threshold in
  `pipeline_v2/filters.py` to "fix" a regression — the gates are
  closed-set by construction, drift requires a logbook entry.
- **Don't** push training data containing lemmas from
  `data/eval/eval_human.jsonl` into the Modal volume — the held-out
  set is the only way to compare lanes fairly.
- **Don't** add arbitrary-code-evaluation (`eval(...)`) to the
  manifest row-filter — the micro-parser at
  `build_modal_corpus.py::_apply_row_filter` is intentional, extend
  it explicitly if the grammar needs to grow.

## Stack at a glance

| Concern | Choice | Notes |
|---|---|---|
| Base LLM (LoRA target) | `mlx-community/c4ai-command-r-08-2024-4bit` | 32B, ~17 GB on disk, runs natively on Apple Silicon. Pinned; bumping is an ADR-class change. **Local inference only — there is no hosted-LLM lane.** |
| Fine-tuning | mlx-lm `lora` | LoRA + DPO. Configs at `scripts/clue_generation/lora_iter*.yaml` (iter9 through iter18 at last count; new iters land alongside, never overwrite). |
| Filter / ranker | `sentence-transformers` w/ CamemBERT base | Triplet contrastive on (lemma, y-clue, n-clue). **Current production filter is whichever the latest eval-logbook iter row promoted** — `ls models/filter-camembert-v*` for the inventory. |
| Morphology | grammalecte lexique 7.7 | `lexique-grammalecte-fr-v7.7.txt` (MPL-2.0). Drives `validate_clue` head/POS lookup. |
| Lexical data (synonyms, defs) | DBnary (Wiktionary RDF) | CC BY-SA. **definition_text** never leaves the offline pipeline. **synonym_lemma** allowed as direct clue per ADR-0024. |
| Validator | `scripts/eval/validate_clue.py` | Pure Python, no model. Output flags listed below — used as a gate before scoring. |
| Eval rating | y / b / n in CSV `rating` column | y=1.0, b=0.5, n=0.0. Self-rating runs ≈10pp stricter than user-rating; don't compare across calibrations. |
| Worker side | Kotlin / Ktor stack — see the `jvm-backend` skill for layer rules | Worker subcommands: `ingest-clue-candidates`, `derive-synonym-clues`, `ingest-dbnary`, `export-words`. |

## Data layout (binding)

```
data/
├── curated/fr.csv               # CC0 hand-authored (lemma, clue) seed pairs.
├── dbnary/dbnary_fr.csv         # parsed DBnary export. Local-only; never deployed.
├── eval/                        # iter samples, generated clues, hand ratings (rating column).
│   ├── sample_100*.csv
│   ├── lemma_clues_iter*.csv    # source-of-truth eval rows; append, never rewrite.
│   └── production/              # full-scale runs (lemma_clues_raw / shipped / dropped).
├── lora/                        # SFT corpus: train.jsonl / valid.jsonl / test.jsonl.
├── lora_filter/                 # filter contrastive corpus.
└── lora_dpo/                    # mined preference pairs (chosen, rejected) from rated iters.
models/
├── lora-clue-v1..vN/            # SFT + DPO adapters. v3 = iter10 SFT base; later v's are DPO on top of v3. Cross-reference `docs/eval/clue-gen-v0.md` to map version → iter; **production stitches the latest promoted adapter with a prior-version fallback** (verify before dispatching — defaults in `run_production.sh` drift behind).
├── filter-camembert-v1..vN/     # ranker checkpoints. Current production = latest promoted in the eval logbook.
└── filter-crossencoder-v1/      # alt cross-encoder explored; not the production path.
```

Anything under `data/` and `models/` is gitignored at scale (large weights, regeneratable corpora). The exception is the rated CSVs under `data/eval/`, which encode human judgement and **must be checked in** when they back a docs/eval iter row.

## ADR-0023 / ADR-0024 — DBnary licence rules

Three constraints are load-bearing. Internalise these before writing any code that reads from `data/dbnary/`:

1. **`definition_text` never leaves the offline pipeline.** Not in a CSV that ships, not in a clue field, not in `clue_candidates.clue_text`, not in a LoRA training pair, not in a prompt sent to a hosted LLM. The filter v1–v5 models train against `(lemma, definition)` positive pairs, but they emit **scores only** — the weights are non-redistributive scratch space (note in `build_filter_corpus.py` head comment).
2. **`synonym_lemma` is allowed as direct `clue_text` only when capitalized per ADR-0024.** First letter uppercased, with `source = 'dbnary-synonym'` on the `clue_candidates` row. Lower-case verbatim DBnary strings are not allowed; the capitalization is the editorial step that distinguishes Bliss output from a DBnary copy.
3. **DBnary data stays in the local-dev / offline tier.** No DBnary content in any deployed artefact, no `dbnary_fr.csv` baked into a worker image, no public URL pointing into `data/dbnary/`. The committed `words-fr.csv` only carries LLM-generated clues + dbnary-synonym capitalized lemmas — both pass the constraints.

If you find yourself wanting to surface DBnary glosses to end users, that's an ADR. Don't sneak it through.

## LoRA training — corpus + config

The SFT corpus is built by `scripts/clue_generation/build_corpus.py`. Inputs:

- `data/curated/fr.csv` — CC0 seed (currently ~62 pairs).
- Hand-rated `y` rows from `data/eval/lemma_clues_iter*.csv` (different valid clues per lemma kept as separate examples — variation, not noise).
- Optional Claude-authored synthetic pairs per `data/lora/synthetic_clues.py` (CC0; iter10 added 400 of these and lifted +7.5pp on the 20-lemma test set).

Configs live at `scripts/clue_generation/lora_iter*.yaml`. The pattern from iter10 onward:
- `train_type: lora`, `train_mode: sft` (or `dpo` for iter12+).
- `num_layers: 16`, `batch_size: 2` for SFT; `batch_size: 4` if rank ≥ 32.
- SFT learning rate ≈ `1e-5`. **DPO learning rate ≈ `1e-6`** (sigmoid loss, β = 0.1) — orders of magnitude lower than SFT. Mixing the two will silently overfit.
- `iters` short enough to stop before val loss climbs. iter10's best was iter 100 (val loss 0.815); iter8's best was iter 200. Always promote the **best-val-loss adapter**, not the last one — train loss keeps falling on a small corpus.
- `resume_adapter_file:` for DPO points at the SFT base adapter (today: `models/lora-clue-v3/adapters.safetensors` — the iter10 SFT). DPO refines preference; it does not replace SFT. Every promoted DPO iter (iter12, iter13.2, iter14, iter17, …) re-DPO'd from `v3` rather than chaining DPO-on-DPO — iter13.1 is the documented counter-example of why chaining drifts.

Train via `scripts/clue_generation/train_lora.sh` or directly:

```
mlx_lm.lora --config scripts/clue_generation/lora_iter12_dpo.yaml
```

When you ship a new adapter:
1. Copy the previous iter's yaml, bump the `iter` number in the path, and document the diff in a header comment.
2. Add the iter row to `docs/eval/clue-gen-v0.md` with acceptance %, train/val loss curve, and the qualitative diff (≥5pp moves at N=80 only).
3. Re-train the filter only when the failure-mode mix changed materially — filter v5 is from iter11's hand-paired (y, n).

## Filter (CamemBERT) — what it does + thresholds

The filter (`models/filter-camembert-vN`) is a sentence-transformers bi-encoder over CamemBERT base, contrastively trained on:
- DBnary `(lemma, sense)` positive pairs (in-batch random negatives).
- Round-2 hand-authored same-lemma `(y, n)` triplets (subtle wrong-sense / polysemic-wrong negatives — exactly the failure modes the validator can't catch).
- Iter `(y, b/n)` pairs from the rated eval CSVs (excluding the held-out `eval_human.jsonl` rows used for measurement).

At inference (`run_production.sh` phase 3), the filter encodes lemma and `lemma_clue` separately and uses cosine similarity as `filter_score`. The default ship threshold is `T = 0.65` (env var `THRESHOLD`). Below T, clue is dropped. Above T **and** validator flag = `ok`, clue is shipped. The hardcoded `FILTER=` default in `run_production.sh` lags production — override on the command line with the version named in the latest eval-logbook iter row.

Don't push T below 0.6 without a fresh held-out eval — the filter starts admitting wrong-sense negatives that look syntactically clean. Don't push T above 0.75 without a fresh eval either — recall on legitimate metaphor / pun clues collapses (`amende → "Contravention financière"` style).

## `validate_clue` flags — the structural gate

`scripts/eval/validate_clue.py` is the gate that runs **before** the filter score. The output `flag` column drives downstream behaviour:

| flag | meaning |
|---|---|
| `ok` | clue passes structural checks. Filter still has to clear `T`. |
| `no-head` | clue has no content-word token (only function words / empty). |
| `unknown-head` | clue's first content word isn't in grammalecte. Hallucination signal. |
| `head-not-lemma` | clue's head is an inflected form, not the citation form. Mots-fléchés convention requires lemma. |
| `pos-mismatch` | clue head is a lemma but the POS class differs from the target lemma's POS. |
| `pleonasm` | the clue's verb already encodes the trailing modifier (`Associer ensemble`, `Monter en haut`, `Prévoir à l'avance`). |
| `stem-leak` (iter7+) | clue token shares ≥5-char prefix with the lemma OR is a substring of the lemma when both are ≥5 chars. |
| `self-leak` | clue contains the lemma or any of its inflected forms. |

The threshold for `stem-leak` is **5 chars** by deliberate choice — 4 catches `couvrir → "Protéger avec une couverture"` but starts firing on Latin/Romance affixes (`pre-`, `con-`, `de-`, `re-`). Don't bump it without re-running the iter7 5-sample variance check (mean 86.0%, stdev 2.5pp).

## Eval methodology — what's measurable, what isn't

From the iter4 regression analysis: **at N=80 with one candidate per lemma, single-iteration variance is ~7pp**. Implications:

- Any structural change (validator rule, prompt tweak) needs to clear ~5pp on N=80 to be distinguishable from noise. Smaller deltas are unmeasurable.
- Once you go to 200+ lemmas with N=3 candidates per lemma + best-of-3 selection, variance drops below 3pp and iter-to-iter comparisons become meaningful. This is the methodological floor for promoting a structural change.
- **Self-rated baseline runs ≈10pp stricter than user-rated.** Never compare a self-rated number to a user-rated number — they're different scales. Mark each iter row in the logbook with `(user)` or `(self)` and only compare like-to-like.
- The 5-sample variance check (run `scripts/eval/run_top_x.sh` with seeds 20260601-05) is the canonical way to confirm a number isn't sample-specific. iter7 sat at 86.0% ± 2.5pp across 5 seeds; that's what "robust" means here.

The `decision rule` table in `docs/eval/clue-gen-v0.md`:
- ≥85% → SHIP (skip further fine-tuning).
- 70–85% → fine-tune (LoRA, then DPO).
- <70% → investigate (prompt, base model, curated set).

That table is the gate for shipping a new adapter into the production pipeline; do not promote an adapter that hasn't cleared the 5-sample variance check at the appropriate decision-rule threshold.

## Production pipeline — `run_production.sh`

Three phases. Resume-safe by design (each phase rewrites its output file in place):

```
# Replace vN with the LoRA adapter + filter version named in the latest
# `docs/eval/clue-gen-v0.md` iter row that promoted a production change.
# The script's own GEN_ADAPTER / FILTER defaults are stale — always override.
X=5000 THRESHOLD=0.65 \
GEN_MODEL=mlx-community/c4ai-command-r-08-2024-4bit \
GEN_ADAPTER=models/lora-clue-vN \
FILTER=models/filter-camembert-vN \
  ./scripts/clue_generation/run_production.sh
```

1. **Sample top-X per length, length-interleaved.** Reads `grid/api/src/main/resources/words/words-fr.csv`, keeps lemmas where `lemma == word` and alphabetic, in length 4–11. Round-robin interleaves so a kill mid-run leaves balanced length coverage. Writes `data/eval/production/sample.jsonl`.
2. **Batched LoRA generation.** `generate_clues_lora_batched.py` runs `mlx_lm.batch_generate` at `batch=16` (M4 Max 64 GB fits comfortably with command-r 4-bit; bump cautiously). The batched path skips per-prompt validate-and-retry — validator runs once and non-`ok` rows are kept with the flag for the filter to drop.
3. **Filter score + threshold split.** Encodes lemma and clue with the filter, writes `filter_score`, then splits into `lemma_clues_shipped.csv` (score ≥ T **and** validator `ok`) and `lemma_clues_dropped.csv`.

The `shipped` CSV is what feeds `bliss-worker ingest-clue-candidates --source <model_version>`. Tag the source consistently (e.g. `command-r-lora-vN-iterMM`) so downstream `findTopBySourcePriority` can prefer or demote it relative to other sources.

## Lemma → surface inflation (the lemma-to-grid bridge)

The LoRA generates clues at **lemma form** — citation form, i.e. infinitive verb / masc-sing noun / masc-sing adjective. The grid, however, contains **surface forms** at arbitrary morphology: `unis` (2sg ipre of `unir` *or* mas-pl ppas), `astres` (mas-pl noun), `abominables` (epi-pl adj), etc. The crossword convention requires the clue's grammar to agree with its surface. Two-stage build closes that gap:

1. **`scripts/eval/inflect_clue.py`** — head-only inflection. Given a surface's grammalecte tags + a lemma-form clue, find the clue's first content-word token whose POS matches the surface, derive the inflectional target (mood + person + gender + number, with the paradigm prefix stripped), and inflect the head via `MorphologyIndex.inflect`. Other tokens stay verbatim. Multi-token agreement (adjective tracking the noun's gender across the clue) is intentionally out of scope — the head-only rule covers the dominant crossword patterns.
2. **`scripts/clue_generation/build_surface_clues.py`** — the per-surface table builder. For every surface in `words-fr.csv` (length 4–11), it determines the owning `(lemma, pos)` using grammalecte's `Total occurrences` with POS precedence `nom > adj > adv > verbe` on ties, then either copies the lemma's clue verbatim (when `surface == lemma`) or inflects the head. Output column `inflection_status` records what happened:

| `inflection_status` | meaning |
|---|---|
| `verbatim` | surface == lemma; clue copied as-is. |
| `inflected` | head token successfully inflected to surface morphology. |
| `identity` | inflected form equals the original (already correct). |
| `no-inflection` | `MorphologyIndex.inflect` couldn't produce the target form (defective paradigm or syncretism mismatch — see PR #193). |
| `head-pos-mismatch` | no token in the clue matches the surface's POS (e.g. clue is all-noun but surface is verb). |
| `no-target-pos` | surface POS not in {nom, adj, verbe}. |
| `no-owner` | no `(lemma, pos)` candidate has a clue in `lemma_clues_shipped.csv`. |

3. **`scripts/clue_generation/merge_clues_into_wordlist.py`** — final assembly. Reads `surface_clues.csv`, keeps `validation_flag == ok` rows above the filter threshold, replaces the placeholder `clue == word` field in the runtime `grid/api/src/main/resources/words/words-fr.csv`. Rows without a high-confidence surface clue keep the placeholder (the grid generator still works; the renderer treats `clue == word` as "no clue available"). The `source` / `source_license` columns describe the **word** provenance (grammalecte, MPL-2.0) — the clue's CC0 LoRA provenance is not surfaced per-field today.

### Hard-won inflation gotchas (fixed in PR #192 + #193 — keep regressions out)

- **Pleonasms in LoRA output propagate via inflation.** iter10 emitted `unir → "Associer ensemble"`; the agreement-aware inflater then faithfully propagated that tautology across **116 surface forms** before anyone noticed. The fix is a closed-set `_find_pleonasm` gate in `validate_clue.py` (`pleonasm` flag) that catches `X + ensemble` for join-verbs, `monter + en haut`, `prévoir + à l'avance`, etc. **Don't widen the gate by analogy** — the closed set is exactly the patterns documented as failures; broader heuristics false-positive on legitimate clues.
- **Syncretic surface tags need paradigm-row splitting.** Grammalecte stores syncretic surface forms on a single row with the *union* of mood/person tags (`unis` carries `{ipre, 1sg, 2sg}`; `accompagne` carries `{ipre, spre, 1sg, 3sg, impe, 2sg}`). The matcher used to require the head verb's paradigm to have one row matching the entire union — irregular verbs whose paradigms split the same syncretism across separate rows (`rends` ipre vs `rende` spre on different rows) returned `no-inflection`. PR #193 changed the matcher to split on tag dimensions; preserve that behaviour.
- **`inv` (invariable) and `epi` (epicene) act as wildcards on either side of the matcher.** `pris` is `{ppas, mas, inv}`; `appartenu` is `{ppas, epi, inv}`. Treat these tags as compatible with anything in their dimension; treating them as hard tags re-introduces a flood of `no-inflection`.
- **`non` head-ranker bug.** Grammalecte tags `non` as both adverb AND mas-inv noun. The naive head ranker captured `Non` as a noun head in clues like `Non présent`; downstream agreement then inherited its `inv` and mis-agreed every following adjective. The fix demotes `non`-as-noun in the head-ranking step — preserve that demotion list and add to it on the same evidence pattern, not on hunches.
- **Defective paradigms are legitimate `no-inflection`.** Some verbs (`soustraire`'s passé simple) have empty grammalecte cells. Ship the lemma form as-is rather than dropping the row or hallucinating a form. The 6 residual `no-inflection` rows in the iter10 export are the canonical example.

### Runtime guard

`scripts/eval/test_runtime_csv_pleonasms.py` is the regression test that asserts:
1. No row in the shipped `grid/api/src/main/resources/words/words-fr.csv` trips `validate_clue._find_pleonasm`.
2. `lemma_clues_shipped.csv` and `surface_clues.csv` both hold zero pleonasm rows.

It's the gate against the "merged-but-not-validated artefact" failure mode (someone hand-edits the CSV and skips the validator). It runs as part of `pytest scripts/eval/`. If it fires, run `python scripts/clue_generation/strip_pleonastic_clues.py` and re-export — don't silence the test.

### Where to plug a new validator rule

A new structural failure mode shows up in three places, in this order:
1. Add the detector + flag value to `scripts/eval/validate_clue.py` (and a unit test under `scripts/eval/test_validate_clue.py`).
2. Wire the flag into the gate in `generate_clues_lora_batched.py` so the production pipeline drops the row before scoring.
3. If the failure mode can sneak past at the surface tier (i.e. a clean lemma clue inflates into a regression — see the pleonasm case), add a runtime guard analogous to `test_runtime_csv_pleonasms.py` over the committed CSV.

Skipping step 3 is what bit PR #192. The validator gate caught new generations, but the existing surface table had already absorbed the bad lemma-form clues; only a runtime test over the committed artefact catches that.

## Synonym derivation — the free-win lane

`bliss-worker derive-synonym-clues` runs the SQL derivation per ADR-0024:

```sql
upper(left(syn.synonym_lemma, 1)) || substring(syn.synonym_lemma, 2)
```

This emits a `clue_candidates` row with `source = 'dbnary-synonym'` for every (lemma, synonym) pair that has the right grammalecte head + matching POS + token frequency above the threshold. The Python prototype lives at `scripts/eval/synonym_clues.py` (single-word preference, freq ≥ 1000 per token, skip parenthesised / hyphenated forms) and the production path is the SQL inside the worker — keep them in sync if you change the picker rules.

The two-source design is deliberate: the synonym lane covers ~30% of nouns at zero cost, and the LLM-generated lane covers the rest. `findTopBySourcePriority` picks per lemma. Don't merge the lanes; don't let the synonym lane's lower-quality picks pre-empt a strong LLM clue.

## `bliss-worker` bridge (Kotlin side)

Subcommands relevant to clue-AI work — see `grid/worker/src/main/kotlin/com/bliss/grid/worker/Main.kt`:

- `ingest-dbnary` — parses `data/dbnary/dbnary_fr.csv` into the `dbnary` table.
- `derive-synonym-clues` — SQL-only synonym derivation per ADR-0024.
- `ingest-clue-candidates` — bulk-loads the LoRA-generated CSV into `clue_candidates`. Required columns: `lemma, clue_text, source`. Optional: `model_version, confidence`. `--truncate` deletes existing rows for the given `--source` before inserting (idempotent re-runs); `--source <override>` and `--model-version <override>` set those columns globally. **This is the only ingestion path for LoRA output** — there's no in-worker generation lane.
- `export-words` — selects the per-lemma top candidate per `findTopBySourcePriority`, writes the committed CSV (`grid/api/src/main/resources/words/words-<lang>.csv`). Sorted by `(language, word)` for stable git diffs. Idempotent.

Cross-layer rules in this corner are the same as for the rest of the JVM backend (see the `jvm-backend` skill): `domain` types like `ClueCandidate` are pure Kotlin, `application` defines the ports, `infrastructure` provides JDBC adapters, `worker` wires Clikt subcommands to use cases.

## Common failure modes (and where they live)

| Symptom | Cause | Fix |
|---|---|---|
| Filter score collapses across the board after retraining | Triplet corpus regenerated with held-out lemmas leaking into train | Re-check `held_out` set against `eval_human.jsonl` in `train_filter_v5.py`. |
| LoRA `val_loss` plateaus then climbs | Overfit on small corpus (iter8 hit this at iter 200 on 85 train pairs) | Promote the best-val-loss adapter; lower lr or fewer iters next run. |
| DPO run goes sideways (acceptance regression) | DPO lr set to SFT lr (1e-5 vs 1e-6 expected) | Lower lr to ~1e-6, β = 0.1, sigmoid loss; resume from SFT adapter, not from scratch. |
| `unknown-head` flags spike | grammalecte lexique not loaded / wrong path | `morphology_index.py` defaults; verify the file at `data/lexique-grammalecte-fr-v7.7.txt`. |
| Stem-leak rule firing on legitimate clues | LCP threshold too low (e.g. dropped to 4) | Restore to 5; add a counter-example to the iter7 variance check before changing. |
| `bliss-worker ingest-clue-candidates` fails on a row | CSV missing required column or non-UTF-8 | Required: `lemma, clue_text, source`. Use UTF-8 (`StandardCharsets.UTF_8` is what the worker expects). |
| `export-words` produces a different CSV on rerun | Tie-break in `findTopBySourcePriority` not deterministic | Fix the SQL ordering: priority, then `created_at`, then `id`. ADR-0013 §7 idempotency is non-negotiable. |
| LoRA inference 10× slower than baseline | Prompt grew (anti-pattern exemplars added back) | iter8+ prompts are intentionally tiny (~30 tokens) — the LoRA learned the style. Keep prompts short. |
| Production-run script killed mid-batch | Normal — phase 1 / 2 / 3 are independently resumable | Re-run `run_production.sh`; phase 1 reuses sample.jsonl, phase 2 picks up incremental progress. |
| Acceptance number swings 7pp between runs | N=80 single-candidate variance | Scale to 200+ lemmas, N=3 per lemma, best-of-3. Anything smaller is unmeasurable noise. |
| `inflection_status` flood of `no-inflection` after a morphology change | Matcher requiring whole-union match on syncretic surface tags | Restore PR #193's per-dimension splitting; do not require one paradigm row to satisfy the surface's full tag union. |
| Surface clue regresses with `inv` / `epi` mismatches | These tags treated as hard constraints in `MorphologyIndex.inflect` | Treat `inv` and `epi` as wildcards on either side of the matcher. |
| Adjective agreement breaks across a clue starting with `Non …` | Head ranker captured `Non` as a mas-inv noun; downstream tokens inherited `inv` | Demote `non`-as-noun in the head-ranking step. |
| Pleonastic clue ships in the runtime CSV | A bad lemma-form clue inflated cleanly across 100+ surfaces | Add the pattern to `_find_pleonasm` in `validate_clue.py`, regenerate, then run `strip_pleonastic_clues.py` to scrub the existing CSV. |

## Don'ts

- **Don't** bake any DBnary `definition_text` into a CSV that ships, into `clue_candidates.clue_text`, or into a LoRA training pair. ADR-0023 constraint #1.
- **Don't** emit a lower-case DBnary `synonym_lemma` as `clue_text`. ADR-0024 only authorises the **capitalized** form, and only when paired with `source = 'dbnary-synonym'`.
- **Don't** extend the ADR-0024 relaxation by analogy (e.g. "if synonyms are fine, glosses must be too"). They aren't. New surfaces require a new ADR + legal review.
- **Don't** commit large weights or full corpus dumps under `data/` or `models/` — those paths are gitignored at scale; only the rated eval CSVs that back a logbook iter row belong in git.
- **Don't** propose a hosted-LLM lane (Anthropic, OpenAI, etc.) as a "quick win" without an ADR. The pipeline is fully local by deliberate decision; reintroducing a hosted call means standing up auth, cost monitoring, retries, prompt caching, and offline-architecture review per ADR-0013.
- **Don't** bump the `command-r-08-2024-4bit` base model in a LoRA config without re-training all downstream adapters and re-running the eval. Adapters are model-specific.
- **Don't** swap SFT and DPO learning rates. SFT ≈ 1e-5, DPO ≈ 1e-6. Wrong LR silently destroys the model.
- **Don't** promote the last-iteration adapter. Always promote best-val-loss — train loss keeps falling on small corpora.
- **Don't** drop the validator gate before scoring. The filter is trained on validator-clean data and behaves badly on `head-not-lemma` / `pos-mismatch` rows it never saw.
- **Don't** lower the stem-leak threshold from 5 chars without rerunning the 5-sample variance check.
- **Don't** silently overwrite an iter row in `docs/eval/clue-gen-v0.md`. Append the new iter; the logbook is the project memory for what's been tried.
- **Don't** compare self-rated and user-rated acceptance numbers directly — there's a ~10pp calibration gap.
- **Don't** mix the LoRA lane and the dbnary-synonym lane into a single `clue_candidates.source` value. The two-source design is what `findTopBySourcePriority` relies on.
- **Don't** add a deployed inference service. The pipeline is local-dev only by ADR-0013 §8; the prod read path is the committed CSV.
- **Don't** generate clues at surface form. The pipeline assumes lemma-form generation + head-token inflation at build time; surface-form generation breaks the dedup that lets one lemma clue cover all of its inflected surfaces.
- **Don't** widen `_find_pleonasm`'s pattern set on intuition. Add a pattern only when there's a concrete failed clue to back it; broad heuristics false-positive on legitimate two-phrase clues.
- **Don't** treat `inv` (invariable) or `epi` (epicene) as hard tags in `MorphologyIndex.inflect`. They are wildcards on either side of the matcher. PR #193 fixed this; don't regress it.
- **Don't** require a single paradigm row to satisfy a syncretic surface's full tag union. Match per dimension. PR #193 fixed this; don't regress it either.
- **Don't** silently swallow `head-pos-mismatch` / `no-owner` / `no-inflection` rows in `build_surface_clues.py`. They mean the surface is shipping with a placeholder or skipped — surface them in the build summary so a human can decide whether to regenerate at the missing POS.
- **Don't** skip `pytest scripts/eval/` before merging a PR that touches `validate_clue.py`, `inflect_clue.py`, `morphology_index.py`, `build_surface_clues.py`, or the committed `words-fr.csv`. The pleonasm runtime guard lives there and is the only thing standing between a hand-edit and a regression on the live grid.
