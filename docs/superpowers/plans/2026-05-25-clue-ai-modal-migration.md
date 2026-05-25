# Modal + HuggingFace clue-AI lane migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the Modal-based cloud-GPU fine-tuning pipeline from
`../bliss-clue-ai` into `bliss` as a second training lane, fused with
the existing MLX lane's curated seeds (gold-weighted) and hard-won
validator gates per [the approved design spec](../specs/2026-05-25-clue-ai-modal-migration-design.md).

**Architecture:** New top-level `modal_jobs/` (paliers 0–3b),
`scripts/clue_generation/modal/` (corpus builder + bridge),
`scripts/clue_generation/pipeline_v2/` (8 ported filters + 2 fused
MLX gates + 8 normalizers + CLI). The MLX lane at
`scripts/clue_generation/` stays untouched; both lanes feed the same
`bliss-worker ingest-clue-candidates` downstream with distinct
`source` tags. Production inference path remains local + committed
CSV (ADR-0013 §8 holds).

**Tech Stack:** Python 3.11; Modal CLI; HuggingFace `transformers
4.45.2 + peft 0.13.2 + trl 0.11.4 + bitsandbytes 0.44.1 + accelerate
1.0.1 + datasets 3.0.1`; Mistral-Nemo-Base-2407 (Apache 2.0);
`lingua-language-detector 2.2.0`; `pytest` for unit tests; stdlib
`tomllib` for the corpus manifest.

**Plan size:** 9 logical PRs (PR 0 → PR 8); PR 3 and PR 4 each split
for the 400-line cap, totalling 11 PRs in practice. Branches follow
`<type>/clue-ai-modal-*` per the spec §6.

**Branch + ADR namespace:** disjoint from the parallel survey
orchestrator (`feat/survey-*`, ADR-0056). This work uses
`*/clue-ai-modal-*` and ADR-0057.

**Source repo for verbatim ports:** `/Users/isho/IdeaProjects/bliss-clue-ai/`
(sibling of `bliss/`, single-commit scratch tree, everything we care
about is untracked there).

---

## File Structure (top-level inventory)

**Created in `bliss/`:**

```
docs/adr/0057-cloud-gpu-modal-finetune-lane.md
docs/clue-style-guide-v2.md
docs/eval/pipeline_v2_pilot_validation.md
data/curated/gold_pilot_v1.csv
data/lora/modal_corpus_v1/
  manifest.toml
  build_summary.md                  (regenerated each build)
  train.jsonl                       (gitignored)
  val.jsonl                         (gitignored)
modal_jobs/
  __init__.py
  README.md
  00_hello_world.py
  01_gpu_check.py
  02_download_mistral.py
  03a_upload_dataset.py
  03b_finetune.py
scripts/clue_generation/modal/
  __init__.py
  prepare_dataset.py
  build_modal_corpus.py
  export_adapter_to_csv.py
  test_prepare_dataset.py
  test_build_modal_corpus.py
  test_export_adapter_to_csv.py
scripts/clue_generation/pipeline_v2/
  __init__.py
  filters.py
  normalizers.py
  llm_judge_mock.py
  run_pipeline.py
  test_filters.py
  test_normalizers.py
  test_negative_cases.py
  requirements.txt
```

**Modified in `bliss/`:**

```
NOTICE.md                                     (lingua + Mistral Nemo entries)
.gitignore                                    (modal_corpus_v1 jsonl)
.claude/skills/clue-ai/SKILL.md               (PR 8 — both lanes documented)
```

**Untouched (load-bearing, do not edit):**

```
scripts/clue_generation/                      (MLX lane stays as-is)
scripts/eval/validate_clue.py                 (runtime guard for committed CSV)
scripts/eval/test_runtime_csv_pleonasms.py    (still runs against validate_clue.py)
data/curated/fr.csv                           (read by build_modal_corpus, NOT modified)
data/curated/short-fr.csv                     (read by build_modal_corpus, NOT modified)
data/lora/synthetic_clues.csv                 (read by build_modal_corpus, NOT modified)
data/eval/lemma_clues_iter*.csv               (read by build_modal_corpus, NOT modified)
data/eval/eval_human.jsonl                    (held-out — explicitly excluded by builder)
```

---

## Wave + dependency map

The cron orchestrator can dispatch up to one wave at a time. Hard
dependencies in **bold**.

| Wave | PRs that can land in parallel | Hard dependency |
|------|-------------------------------|-----------------|
| 1    | PR 0 (ADR-0057)               | (nothing; blocks all code) |
| 2    | PR 1 (style guide), PR 2 (gold pilot CSV), PR 5 (Modal paliers 0-2) | **PR 0** |
| 3    | PR 3a (pipeline_v2 verbatim), PR 4a (prepare_dataset.py + manifest skeleton) | **PR 0**; PR 4a also depends on **PR 2** |
| 4    | PR 3b (pipeline_v2 fusion), PR 4b (build_modal_corpus.py) | **PR 3a**, **PR 4a** respectively |
| 5    | PR 6 (Modal paliers 3a + 3b)  | **PR 4b** (or 4a for gold-only smoke), **PR 5** |
| 6    | PR 7 (export bridge)          | **PR 3b**, **PR 6**          |
| 7    | PR 8 (skill update + pilot validation report) | **PR 7** |

After each wave's PRs squash-merge, stacked descendants rebase per
[[feedback-stacked-pr-rebase]]:
```
git fetch origin
git rebase --onto origin/main <last-base-commit> <stacked-branch>
```

---

## Conventions (apply to every PR)

- **Branch naming:** `<type>/clue-ai-modal-<short-slug>` where
  `<type>` ∈ {`feat`, `fix`, `chore`, `refactor`, `test`, `docs`}.
  Enforced by `branch-name.yml` per [[feedback-commitlint-gotchas]].
- **Commit format:** `<type>(<scope>): <subject>` with scope including
  `clue-ai-modal`. Example: `chore(clue-ai-modal): port pipeline_v2 filters 1-8`. Subject lower-case, no trailing period, ≤72 chars.
- **DCO sign-off:** every commit with `git commit -s`. Pre-existing
  in repo via commit-commands skill.
- **No `--no-verify`, no `--no-gpg-sign`, no force-push to main.**
- **400-line cap:** invoke the standing cap override per
  [[feedback-standing-cap-override]] in the PR body when justified.
  Pre-flag at dispatch per [[feedback-cap-override-short-circuit]].
- **PR title** mirrors the commit subject. PR body names the
  workstream (`clue-ai-modal`), the bounded surface (this is a
  `scripts/` + `modal_jobs/` workstream — there's no JVM context),
  any schemas shipped first, and the wave number.

---

## Task 1: PR 0 — ADR-0057 (cloud-GPU lane)

**Wave:** 1. **Branch:** `docs/adr-0057-modal-clue-finetune-lane`.

**Files:**
- Create: `docs/adr/0057-cloud-gpu-modal-finetune-lane.md`

This PR is the maintainer-approval gate. No code lands until it
merges.

- [ ] **Step 1: Verify ADR number is free**

Run:
```
ls docs/adr/0057-*.md docs/adr/0056-*.md 2>&1
```
Expected: `0057-*.md` does not exist. (`0056-*.md` may already exist on
`main` if the parallel survey orchestrator's ADR has landed — that's
fine; 0057 is still ours.)

If `0057` is somehow taken (race), bump to the next free number and
update all references in this plan + the spec.

- [ ] **Step 2: Write the ADR**

Create `docs/adr/0057-cloud-gpu-modal-finetune-lane.md` using the
template in `CLAUDE.md` ("ADR template" section):

```markdown
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
```

- [ ] **Step 3: Commit and open PR**

```
git add docs/adr/0057-cloud-gpu-modal-finetune-lane.md
git commit -s -m "docs(adr): add ADR-0057 cloud-GPU Modal clue-AI fine-tuning lane"
git push -u origin docs/adr-0057-modal-clue-finetune-lane
gh pr create --title "docs(adr): add ADR-0057 cloud-GPU Modal clue-AI fine-tuning lane" --body "..."
```

PR body (commit-commands skill's HEREDOC template):
```
## Summary
- Adds ADR-0057 introducing the Modal + HuggingFace cloud-GPU lane as
  a peer to the existing local-MLX training lane (ADR-0013).
- Training-only; production inference stays local + committed CSV per
  ADR-0013 §8.
- Modal is the first paid third-party in the repo (CLAUDE.md "Don'ts");
  per-function `timeout=` bounds spend, pilot run ≈ $1.50.
- Includes threat model for `HF_TOKEN` + Modal API key surface.

## Wave
Wave 1 of clue-ai-modal-migration (gates everything downstream).

## Test plan
- [ ] ADR template followed (Status / Context / Decision / Consequences)
- [ ] Threat model section per CLAUDE.md ("Auth/authz changes need a threat model")
- [ ] References ADR-0013, ADR-0001, the clue-ai skill, and the design spec
```

- [ ] **Step 4: Wait for maintainer to flip Status to Accepted**

This is the human gate. No subsequent PR opens until ADR is merged.

---

## Task 2: PR 1 — Style guide v2 (docs)

**Wave:** 2. **Branch:** `docs/clue-style-guide-v2`. **Depends on:** PR 0.

**Files:**
- Create: `docs/clue-style-guide-v2.md`
- Modify: `NOTICE.md` (add `lingua-language-detector` Apache 2.0 entry)

This is a documentation port with two amendments (filters 9 + 10).

- [ ] **Step 1: Verify source style guide is reachable**

Run:
```
ls -la /Users/isho/IdeaProjects/bliss-clue-ai/docs/style_guide.md
```
Expected: file exists. Read its full length to know what we're porting.

- [ ] **Step 2: Copy style guide and add amendments**

```
cp /Users/isho/IdeaProjects/bliss-clue-ai/docs/style_guide.md \
   docs/clue-style-guide-v2.md
```

Then prepend a frontmatter block referencing ADR-0057, and amend the
`§8.3 Filtres` section to add two new filter entries (9 + 10).

Insert at the top of `docs/clue-style-guide-v2.md`:
```markdown
> **Lane scope:** This style guide drives the Modal + HuggingFace
> clue-AI lane (ADR-0057, Mistral-Nemo-Base-2407 target). The
> existing local-MLX / Command-R lane is documented in
> `.claude/skills/clue-ai/SKILL.md` and `docs/eval/clue-gen-v0.md`;
> the two lanes share `validate_clue.py`'s structural gates only
> through the fused `pipeline_v2` validator (this doc's §8.3
> filters 9 + 10).

```

Then in the `§8.3` filter list, after `filter_8_llm_juge_mock`, add:

```markdown
### filter_9_stem_leak — interdiction stem-leak (porté du lane MLX)

**Critère.** Rejette si un token de la définition partage un préfixe
≥ 5 caractères avec le lemme, OU est une sous-chaîne du lemme quand
les deux ont ≥ 5 caractères. Seuil délibéré à 5 chars : 4 catche
`couvrir → "Protéger avec une couverture"` mais déclenche sur les
affixes latins / romans (`pre-`, `con-`, `de-`, `re-`).

**Provenance.** Porté verbatim de
`scripts/eval/validate_clue.py::_find_stem_leak` (iter7+). Ne pas
abaisser le seuil sans rejouer la 5-sample variance check de iter7
(86.0 % ± 2.5 pp sur seeds 20260601-05).

### filter_10_pleonasm — interdiction pleonasm (porté du lane MLX)

**Critère.** Rejette si la définition matche un pattern de pleonasme
de l'ensemble fermé documenté ci-dessous : `Associer ensemble`,
`Monter en haut`, `Prévoir à l'avance`, etc. La liste vit en clair
dans `pipeline_v2/filters.py::_PLEONASM_PATTERNS`.

**Provenance.** Porté verbatim de
`scripts/eval/validate_clue.py::_find_pleonasm` (PR #192). Le pattern
set est fermé par construction : l'élargir par analogie a déjà causé
des faux positifs sur des définitions à deux syntagmes légitimes.
Toute extension exige une iter row dans `docs/eval/clue-gen-v0.md`
documentant le clue échec concret qui motive l'ajout.
```

- [ ] **Step 3: Update NOTICE.md**

Append the `lingua-language-detector` entry to the appropriate
attribution section in `NOTICE.md`. Open the file, find the
attribution list, add:

```
- lingua-language-detector v2.2.0 — Apache 2.0
  https://github.com/pemistahl/lingua-py
  Used by scripts/clue_generation/pipeline_v2/filters.py for FR/EN
  classification (§8.3 filter 6).
```

If a Mistral Nemo entry is needed (the model is downloaded at
training time, not bundled), add a defensive note:

```
- Mistral-Nemo-Base-2407 — Apache 2.0 (model downloaded at training
  time; not bundled in any deployed artefact). Used by the Modal
  fine-tuning lane (ADR-0057).
```

- [ ] **Step 4: Commit and PR**

```
git add docs/clue-style-guide-v2.md NOTICE.md
git commit -s -m "docs(clue-ai-modal): port style guide v2 with filters 9 + 10 amendments"
git push -u origin docs/clue-style-guide-v2
gh pr create --title "docs(clue-ai-modal): style guide v2 + filters 9 + 10" --body "..."
```

PR body must cite ADR-0057, the spec §2.1 (verbatim port) and §3.5.1
(MLX-fusion amendments), and the wave (Wave 2).

---

## Task 3: PR 2 — Gold pilot v1 seed corpus (data)

**Wave:** 2. **Branch:** `chore/clue-ai-modal-gold-pilot-v1`.

**Files:**
- Create: `data/curated/gold_pilot_v1.csv` (114 rows + header)

- [ ] **Step 1: Verify source CSV is reachable and inspect schema**

Run:
```
head -2 /Users/isho/IdeaProjects/bliss-clue-ai/data/seed/gold_pilot_v1.csv
wc -l /Users/isho/IdeaProjects/bliss-clue-ai/data/seed/gold_pilot_v1.csv
```
Expected: header `mot;definition;pos;categorie;style;force;longueur;source;meta` + 114 data rows.

- [ ] **Step 2: Copy with no edits**

```
cp /Users/isho/IdeaProjects/bliss-clue-ai/data/seed/gold_pilot_v1.csv \
   data/curated/gold_pilot_v1.csv
```

The CSV uses `;` separator (style-guide-v2 convention); no schema
change. The file is committed as-is so `build_modal_corpus.py` reads
it unchanged.

- [ ] **Step 3: Verify byte equality**

Run:
```
diff /Users/isho/IdeaProjects/bliss-clue-ai/data/seed/gold_pilot_v1.csv \
     data/curated/gold_pilot_v1.csv
```
Expected: no output (files identical).

- [ ] **Step 4: Spot-check encoding**

Run:
```
file -I data/curated/gold_pilot_v1.csv
```
Expected: `charset=utf-8`. If charset is detected as anything else
(e.g. iso-8859-1), abort — the source must already be UTF-8 per the
style-guide-v2 pipeline assumption.

- [ ] **Step 5: Commit and PR**

```
git add data/curated/gold_pilot_v1.csv
git commit -s -m "feat(clue-ai-modal): seed gold pilot v1 corpus (114 CC0 entries)"
git push -u origin chore/clue-ai-modal-gold-pilot-v1
gh pr create --title "feat(clue-ai-modal): seed gold pilot v1 corpus" --body "..."
```

PR body cites: spec §2.1, ADR-0057, and that the file is CC0 hand-
curated and is the gold tier for the Modal-lane corpus builder.

---

## Task 4: PR 5 — Modal paliers 0–2 (chore)

**Wave:** 2. **Branch:** `chore/clue-ai-modal-paliers-0-1-2`. (Yes, this
PR is in Wave 2 even though it's numbered after PR 3/PR 4 — Modal infra
smoke tests don't depend on validator or corpus. Wave numbering reflects
dependency, not numerical order.)

**Files:**
- Create: `modal_jobs/__init__.py` (empty)
- Create: `modal_jobs/README.md`
- Create: `modal_jobs/00_hello_world.py`
- Create: `modal_jobs/01_gpu_check.py`
- Create: `modal_jobs/02_download_mistral.py`

- [ ] **Step 1: Create the empty package marker**

```
mkdir -p modal_jobs
touch modal_jobs/__init__.py
```

- [ ] **Step 2: Write the runbook README**

Create `modal_jobs/README.md`:

```markdown
# Modal jobs — cloud-GPU clue-AI training lane

Implements ADR-0057. **Training only.** Production inference path is
local + committed CSV per ADR-0013 §8.

## One-time setup (per developer)

1. Install Modal CLI: `pip install modal-client`.
2. Authenticate: `modal token new`.
3. Accept the Mistral licence on HF Hub (same account whose token
   you'll upload). Visit
   <https://huggingface.co/mistralai/Mistral-Nemo-Base-2407>, click
   "Accept" on the licence prompt.
4. Create the Modal secret:
   `modal secret create huggingface HF_TOKEN=hf_xxxx`.

## Paliers (run in order on first install)

| # | Script | Cost | Time | Validates |
|---|--------|------|------|-----------|
| 0 | `modal run modal_jobs/00_hello_world.py` | ~$0.01 | ~10 s | Modal CLI + auth |
| 1 | `modal run modal_jobs/01_gpu_check.py` | ~$0.02 | ~30 s | A100-40GB available |
| 2 | `modal run modal_jobs/02_download_mistral.py` | ~$0.05 | 5–15 min (first time) | `huggingface` secret + licence accept + volume persistence |
| 3a | `modal run modal_jobs/03a_upload_dataset.py` | ~$0.01 | ~15 s | dataset volume reachable from local |
| 3b | `modal run modal_jobs/03b_finetune.py` | ~$1.50 | ~25–35 min | end-to-end QLoRA SFT |

After palier 2, the model lives on volume `mots-fleches-models` and
is reused by every subsequent run (idempotent — re-running 02 reports
`skipped` immediately).

## Killing a runaway run

```
modal app stop mots-fleches-finetune
```

(replace app name per the script — `mots-fleches-download` for
palier 2, `mots-fleches-upload-dataset` for 3a, etc.)

Each palier also has a defensive `timeout=` (1800 s palier 2, 3600 s
palier 3b) — a runaway dies on its own within an hour.

## Costs

A100-40GB on Modal is $3.10/hr at the time of ADR-0057. Pilot
end-to-end run total ≈ $1.50. Storage on `mots-fleches-models`
volume ≈ $0.13/month for ~24 GB. CPU/network during paliers 0/1/3a is
fractions of a cent per run.
```

- [ ] **Step 3: Port palier 0 (hello world)**

```
cp /Users/isho/IdeaProjects/bliss-clue-ai/modal_jobs/00_hello_world.py \
   modal_jobs/00_hello_world.py
```

- [ ] **Step 4: Port palier 1 (gpu check)**

```
cp /Users/isho/IdeaProjects/bliss-clue-ai/modal_jobs/01_gpu_check.py \
   modal_jobs/01_gpu_check.py
```

- [ ] **Step 5: Port palier 2 (model download)**

```
cp /Users/isho/IdeaProjects/bliss-clue-ai/modal_jobs/02_download_mistral.py \
   modal_jobs/02_download_mistral.py
```

The file is ~250 lines, fully self-contained, no edits needed beyond
the copy.

- [ ] **Step 6: Verify ports are byte-identical**

Run:
```
for f in 00_hello_world.py 01_gpu_check.py 02_download_mistral.py; do
  diff /Users/isho/IdeaProjects/bliss-clue-ai/modal_jobs/$f modal_jobs/$f
done
```
Expected: no diff output for any file.

- [ ] **Step 7: Static syntax check**

```
python3 -m py_compile modal_jobs/00_hello_world.py \
                       modal_jobs/01_gpu_check.py \
                       modal_jobs/02_download_mistral.py
```
Expected: no output (compilation succeeds).

- [ ] **Step 8: Manual smoke test (developer-local)**

This step is NOT part of CI. After PR merge, the maintainer runs:
```
modal run modal_jobs/00_hello_world.py    # ~$0.01
modal run modal_jobs/01_gpu_check.py      # ~$0.02
modal run modal_jobs/02_download_mistral.py  # ~$0.05 + 5-15 min first time
```
Expected: palier 0 prints "hello"; palier 1 reports A100-40GB; palier
2 reports `downloaded` on first run, `skipped` on second. Document
the actual outputs in the PR body's "Test plan" section.

- [ ] **Step 9: Commit and PR**

PR will be ≈ 435 lines (250 + 60 + 25 + ~100 README + empty __init__).
Pre-flag cap override per [[feedback-cap-override-short-circuit]]: the
README plus three scripts ARE a single workstream and splitting hurts
review by separating runbook from runbook target.

```
git add modal_jobs/
git commit -s -m "chore(clue-ai-modal): port Modal paliers 0-2 (hello world, gpu check, model download)"
git push -u origin chore/clue-ai-modal-paliers-0-1-2
gh pr create --title "chore(clue-ai-modal): Modal paliers 0-2 (smoke + Mistral download)" --body "..."
```

PR body includes:
- The standing cap override invocation: "Pre-flagged: 435 lines is
  above the 400-line cap; the README + 3 paliers are one workstream
  (runbook references each script by path) and splitting would force
  reviewers to context-switch between runbook and target on each PR."
- The manual smoke results from Step 8.

---

## Task 5: PR 3a — pipeline_v2 verbatim port (chore)

**Wave:** 3. **Branch:** `chore/clue-ai-modal-pipeline-v2-port`.
**Depends on:** PR 0.

**Files:**
- Create: `scripts/clue_generation/pipeline_v2/__init__.py`
- Create: `scripts/clue_generation/pipeline_v2/filters.py` (filters 1-7)
- Create: `scripts/clue_generation/pipeline_v2/llm_judge_mock.py` (filter 8)
- Create: `scripts/clue_generation/pipeline_v2/normalizers.py`
- Create: `scripts/clue_generation/pipeline_v2/test_filters.py` (per-filter unit tests)
- Create: `scripts/clue_generation/pipeline_v2/test_normalizers.py`
- Create: `scripts/clue_generation/pipeline_v2/test_negative_cases.py` (27 cases from source)
- Create: `scripts/clue_generation/pipeline_v2/requirements.txt`

- [ ] **Step 1: Create the empty package marker + requirements.txt**

```
mkdir -p scripts/clue_generation/pipeline_v2
touch scripts/clue_generation/pipeline_v2/__init__.py
```

Create `scripts/clue_generation/pipeline_v2/requirements.txt`:
```
lingua-language-detector==2.2.0
```

That's the only runtime dependency for filters 1-8. (Filters 9 + 10
in PR 3b need only stdlib — no requirements change.)

- [ ] **Step 2: Port filters.py**

```
cp /Users/isho/IdeaProjects/bliss-clue-ai/scripts/pipeline/filters.py \
   scripts/clue_generation/pipeline_v2/filters.py
```

- [ ] **Step 3: Port normalizers.py**

```
cp /Users/isho/IdeaProjects/bliss-clue-ai/scripts/pipeline/normalizers.py \
   scripts/clue_generation/pipeline_v2/normalizers.py
```

- [ ] **Step 4: Port llm_judge_mock.py**

```
cp /Users/isho/IdeaProjects/bliss-clue-ai/scripts/pipeline/llm_judge_mock.py \
   scripts/clue_generation/pipeline_v2/llm_judge_mock.py
```

- [ ] **Step 5: Port test_negative_cases.py**

```
cp /Users/isho/IdeaProjects/bliss-clue-ai/scripts/pipeline/test_negative_cases.py \
   scripts/clue_generation/pipeline_v2/test_negative_cases.py
```

The 27-case suite at the source covers filters 1-8. PR 3b extends it
to filters 9 + 10.

- [ ] **Step 6: Verify byte-identical ports**

```
for f in filters.py normalizers.py llm_judge_mock.py test_negative_cases.py; do
  diff /Users/isho/IdeaProjects/bliss-clue-ai/scripts/pipeline/$f \
       scripts/clue_generation/pipeline_v2/$f
done
```
Expected: no diff output.

- [ ] **Step 7: Write a placeholder test_filters.py and test_normalizers.py**

These are *additional* unit tests beyond the 27 negative cases. They
target per-filter happy paths so regressions in PR 3b's added filter
9 + 10 logic don't accidentally break a verbatim filter.

Create `scripts/clue_generation/pipeline_v2/test_filters.py`:

```python
"""Per-filter happy-path unit tests (complement to test_negative_cases).

The negative-cases suite verifies that bad inputs trip the right
filter. These tests verify that legitimate inputs do NOT trip any
filter, per-filter. Together they pin down the calibration.
"""

from __future__ import annotations

import pytest

from . import filters as F


# Minimal-row helper. The pipeline filters read `definition` and the
# style/POS-aware filters also read metadata columns.
def _row(mot: str, definition: str, **kw) -> dict:
    base = {
        "mot": mot,
        "definition": definition,
        "pos": kw.get("pos", "nom_commun"),
        "categorie": kw.get("categorie", "autre"),
        "style": kw.get("style", "definition_directe"),
        "force": kw.get("force", "1"),
        "longueur": kw.get("longueur", str(len(definition.split()))),
        "source": kw.get("source", "gold_pilot_v1"),
        "meta": kw.get("meta", ""),
    }
    base.update(kw)
    return base


def test_filter_1_typographiques_accepts_clean_french():
    r = _row("POMME", "Tentation d’Ève")
    out = F.filter_1_typographiques(r)
    assert out.action == "accept", out.reason


def test_filter_3_longueur_accepts_short_clue():
    r = _row("COQ", "Mâle de la basse-cour")
    out = F.filter_3_longueur(r)
    assert out.action == "accept", out.reason


def test_filter_5_auto_reference_accepts_unrelated_substring():
    # "rio" substring inside "carioca" is NOT an auto-reference for RIO
    r = _row("RIO", "Habitant de carioca")
    out = F.filter_5_auto_reference(r)
    assert out.action == "accept", out.reason


def test_filter_6_langue_fr_accepts_well_calibrated_french():
    r = _row("CHAPEAU", "Couvre-chef classique")
    out = F.filter_6_langue_fr(r)
    assert out.action == "accept", out.reason


def test_filter_7_tautologie_accepts_specific_clue():
    r = _row("CHAT", "Félin domestique courant")
    out = F.filter_7_tautologie(r)
    assert out.action == "accept", out.reason
```

Create `scripts/clue_generation/pipeline_v2/test_normalizers.py`:

```python
"""Per-normalizer unit tests. Each normalizer is a pure str → str."""

from __future__ import annotations

import unicodedata

from . import normalizers as N


def test_norm_1_apostrophe_curly_quotes_left_alone():
    out, applied = N.norm_1_apostrophe("Forme d’avoir")
    assert out == "Forme d’avoir"
    assert "norm_1_apostrophe" not in applied


def test_norm_1_apostrophe_straight_to_curly():
    out, applied = N.norm_1_apostrophe("Forme d'avoir")
    assert out == "Forme d’avoir"
    assert "norm_1_apostrophe" in applied


def test_norm_7_nfc_idempotent_on_nfc():
    nfc = "Épaule"
    out, applied = N.norm_7_nfc(nfc)
    assert out == nfc
    assert "norm_7_nfc" not in applied


def test_norm_7_nfc_converts_nfd_to_nfc():
    nfd = unicodedata.normalize("NFD", "Épaule")
    out, applied = N.norm_7_nfc(nfd)
    assert unicodedata.is_normalized("NFC", out)
    assert "norm_7_nfc" in applied
```

The exact names (`norm_1_apostrophe`, `filter_6_langue_fr`, etc.)
must match what the source defines — verify by reading the ported
`filters.py` and `normalizers.py` before committing.

- [ ] **Step 8: Run the test suite locally**

```
cd /Users/isho/IdeaProjects/bliss
python3 -m pip install -r scripts/clue_generation/pipeline_v2/requirements.txt
python3 -m pytest scripts/clue_generation/pipeline_v2/ -v
```
Expected: all happy-path tests pass; all 27 negative-cases tests pass.

If any negative case fails, do NOT silence it — the failing case is a
calibration drift between the source `bliss-clue-ai` runtime and the
bliss runtime (likely different `lingua` version or NFD/NFC handling).
Investigate and fix before merging.

- [ ] **Step 9: Commit and PR**

PR is ~700 lines (filters ~280 + normalizers ~150 + llm_judge ~60 +
negative ~250 + happy-path ~130). Pre-flag cap override.

```
git add scripts/clue_generation/pipeline_v2/
git commit -s -m "chore(clue-ai-modal): port pipeline_v2 filters 1-8 + normalizers (verbatim)"
git push -u origin chore/clue-ai-modal-pipeline-v2-port
gh pr create --title "chore(clue-ai-modal): pipeline_v2 verbatim port (PR 3a)" --body "..."
```

PR body invokes cap override: "Pre-flagged: ~700 lines is above the
400-line cap; this is one workstream (the verbatim port of the
style-guide-v2 validator from bliss-clue-ai) and splitting would
force reviewers to context-switch between filter implementations and
their happy-path tests. The MLX-fusion follow-up is in PR 3b on a
separate branch."

---

## Task 6: PR 3b — pipeline_v2 MLX fusion (chore)

**Wave:** 4. **Branch:** `chore/clue-ai-modal-pipeline-v2-fusion`.
**Depends on:** PR 3a.

**Files:**
- Modify: `scripts/clue_generation/pipeline_v2/filters.py` (add `filter_9_stem_leak` + `filter_10_pleonasm`)
- Modify: `scripts/clue_generation/pipeline_v2/test_negative_cases.py` (extend with stem-leak + pleonasm cases)
- Modify: `scripts/clue_generation/pipeline_v2/test_filters.py` (happy-path tests for 9 + 10)
- Create: `scripts/clue_generation/pipeline_v2/run_pipeline.py` (CLI orchestrator)

- [ ] **Step 1: Read the source-of-truth detector logic**

Before writing tests, read these two methods in the MLX lane to
understand the exact behavior we're cloning:

```
sed -n '/def _find_stem_leak/,/^def /p' scripts/eval/validate_clue.py | head -60
sed -n '/def _find_pleonasm/,/^def /p' scripts/eval/validate_clue.py | head -120
```

Note: the source signatures take `clue: str, lemma: str` and return a
truthy match or None. Our `pipeline_v2` filters take a `row: dict`
and return a `FilterResult` (action / reason). The port wraps the
detector and shapes its result.

- [ ] **Step 2: Write the failing test for filter_9_stem_leak**

Append to `test_negative_cases.py` (find the existing test list and
add these cases in the same style):

```python
def test_filter_9_stem_leak_rejects_5_char_prefix_share():
    # "Protéger" shares prefix "prote" (5+ chars) with "PROTECTION"
    from . import filters as F
    r = {
        "mot": "PROTECTION",
        "definition": "Protéger un objet",
        "pos": "nom_commun", "categorie": "autre",
        "style": "definition_directe", "force": "1",
        "longueur": "3", "source": "test", "meta": "",
    }
    out = F.filter_9_stem_leak(r)
    assert out.action == "reject"
    assert "stem-leak" in out.reason.lower()


def test_filter_9_stem_leak_accepts_4_char_prefix_share():
    # "Prés" + "PRESIDENT" share 4 chars only — below the 5-char threshold
    from . import filters as F
    r = {
        "mot": "PRESIDENT",
        "definition": "Prés du sénat",
        "pos": "nom_commun", "categorie": "autre",
        "style": "definition_directe", "force": "1",
        "longueur": "3", "source": "test", "meta": "",
    }
    out = F.filter_9_stem_leak(r)
    assert out.action == "accept", out.reason


def test_filter_9_stem_leak_rejects_substring_in_long_lemma():
    # "couvert" is a substring of "COUVERTURE" — both ≥ 5 chars
    from . import filters as F
    r = {
        "mot": "COUVERTURE",
        "definition": "Mettre un couvert",
        "pos": "nom_commun", "categorie": "autre",
        "style": "definition_directe", "force": "1",
        "longueur": "3", "source": "test", "meta": "",
    }
    out = F.filter_9_stem_leak(r)
    assert out.action == "reject"
    assert "stem-leak" in out.reason.lower()


def test_filter_10_pleonasm_rejects_associer_ensemble():
    from . import filters as F
    r = {
        "mot": "UNIR",
        "definition": "Associer ensemble",
        "pos": "verbe_infinitif", "categorie": "autre",
        "style": "definition_directe", "force": "1",
        "longueur": "2", "source": "test", "meta": "",
    }
    out = F.filter_10_pleonasm(r)
    assert out.action == "reject"
    assert "pleonasm" in out.reason.lower()


def test_filter_10_pleonasm_rejects_monter_en_haut():
    from . import filters as F
    r = {
        "mot": "GRIMPER",
        "definition": "Monter en haut",
        "pos": "verbe_infinitif", "categorie": "autre",
        "style": "definition_directe", "force": "1",
        "longueur": "3", "source": "test", "meta": "",
    }
    out = F.filter_10_pleonasm(r)
    assert out.action == "reject"


def test_filter_10_pleonasm_rejects_prevoir_a_l_avance():
    from . import filters as F
    r = {
        "mot": "ANTICIPER",
        "definition": "Prévoir à l'avance",
        "pos": "verbe_infinitif", "categorie": "autre",
        "style": "definition_directe", "force": "1",
        "longueur": "3", "source": "test", "meta": "",
    }
    out = F.filter_10_pleonasm(r)
    assert out.action == "reject"


def test_filter_10_pleonasm_accepts_legitimate_two_phrase_clue():
    # "Quitter en partant" is NOT in the closed pleonasm set even
    # though it has a "redundant" feel — closed-set by design.
    from . import filters as F
    r = {
        "mot": "DEPART",
        "definition": "Quitter en partant",
        "pos": "nom_commun", "categorie": "autre",
        "style": "definition_directe", "force": "1",
        "longueur": "3", "source": "test", "meta": "",
    }
    out = F.filter_10_pleonasm(r)
    assert out.action == "accept", out.reason
```

Add happy-path companions to `test_filters.py`:

```python
def test_filter_9_stem_leak_accepts_clean_definition():
    from . import filters as F
    r = _row("CHAT", "Félin domestique courant")
    assert F.filter_9_stem_leak(r).action == "accept"


def test_filter_10_pleonasm_accepts_clean_definition():
    from . import filters as F
    r = _row("CHIEN", "Animal fidèle au gardien")
    assert F.filter_10_pleonasm(r).action == "accept"
```

- [ ] **Step 3: Run tests to verify they fail**

```
python3 -m pytest scripts/clue_generation/pipeline_v2/ -v -k "filter_9 or filter_10"
```
Expected: AttributeError / FAILED on every new test ("module 'filters'
has no attribute 'filter_9_stem_leak'"). This is the red of TDD.

- [ ] **Step 4: Port `_find_stem_leak` from validate_clue.py**

Read the source method from `scripts/eval/validate_clue.py` (the one
the MLX lane uses; see Step 1's `sed` command). Copy its core logic
into `scripts/clue_generation/pipeline_v2/filters.py` as
`_stem_leak_match(clue: str, lemma: str) -> str | None`, preserving:
- The 5-char threshold (CRITICAL — do NOT lower).
- The `unicodedata.normalize("NFD", …)` + `strip combining marks`
  preprocessing for accent-insensitive comparison.
- The "either prefix-share ≥ 5 OR substring when both ≥ 5" rule.

Then wrap it in a `FilterResult`-returning function appended to
`pipeline_v2/filters.py`:

```python
# === filter_9_stem_leak — porté de scripts/eval/validate_clue.py ===
# Seuil 5 chars : volontaire. Voir docs/eval/clue-gen-v0.md iter7
# (variance check 5 seeds, 86.0% ± 2.5pp). Ne pas abaisser sans
# rejouer la variance check.

def _strip_accents(s: str) -> str:
    """NFD + drop combining marks. Used for accent-insensitive match."""
    import unicodedata
    nfd = unicodedata.normalize("NFD", s)
    return "".join(c for c in nfd if not unicodedata.combining(c))


def _stem_leak_match(clue: str, lemma: str) -> str | None:
    """Return the leaking token, or None. Mirrors validate_clue.py."""
    clue_norm = _strip_accents(clue.lower())
    lemma_norm = _strip_accents(lemma.lower())

    if len(lemma_norm) < 5:
        return None

    for token in clue_norm.split():
        if len(token) < 5:
            continue
        # Rule A: shared prefix ≥ 5 chars
        prefix_len = 0
        for a, b in zip(token, lemma_norm):
            if a != b:
                break
            prefix_len += 1
        if prefix_len >= 5:
            return token
        # Rule B: token is substring of lemma (both ≥ 5 chars by this point)
        if token in lemma_norm:
            return token
        # Rule B': lemma substring of token (5+ char overlap either way)
        if lemma_norm in token:
            return token
    return None


def filter_9_stem_leak(row: dict) -> FilterResult:
    """Reject if a clue token leaks the lemma's stem."""
    leak = _stem_leak_match(row["definition"], row["mot"])
    if leak is None:
        return FilterResult(action="accept", reason="")
    return FilterResult(
        action="reject",
        reason=f"stem-leak: token '{leak}' shares ≥5-char stem with lemma '{row['mot']}'",
    )
```

(`FilterResult` is already the dataclass / namedtuple defined in the
ported `filters.py` from PR 3a — verify by reading the file and reuse
the exact same import / definition.)

- [ ] **Step 5: Port `_find_pleonasm`**

Read the closed pattern set from `scripts/eval/validate_clue.py::_find_pleonasm`
and replicate it verbatim. The patterns are case-insensitive regex
fragments; the exact list is the source of truth.

Append to `pipeline_v2/filters.py`:

```python
# === filter_10_pleonasm — porté de scripts/eval/validate_clue.py ===
# Ensemble fermé par construction. PR #192 a corrigé l'inflation
# pleonastique d'iter10 (`unir → "Associer ensemble"` x116 surfaces).
# Élargir l'ensemble exige un clue échec concret en logbook, jamais
# par analogie.
import re

_PLEONASM_PATTERNS: tuple[re.Pattern[str], ...] = tuple(
    re.compile(p, re.IGNORECASE)
    for p in (
        # Join-verbs + ensemble
        r"\bassocier\s+ensemble\b",
        r"\bréunir\s+ensemble\b",
        r"\bjoindre\s+ensemble\b",
        r"\bunir\s+ensemble\b",
        # Monter + en haut
        r"\bmonter\s+en\s+haut\b",
        r"\bdescendre\s+en\s+bas\b",
        # Prévoir + à l'avance / d'avance
        r"\bprévoir\s+à\s+l['’]avance\b",
        r"\bprévoir\s+d['’]avance\b",
        # Sortir + dehors / Entrer + dedans
        r"\bsortir\s+dehors\b",
        r"\bentrer\s+dedans\b",
        # Reculer + en arrière / Avancer + en avant
        r"\breculer\s+en\s+arrière\b",
        r"\bavancer\s+en\s+avant\b",
    )
)


def _pleonasm_match(text: str) -> str | None:
    """Return the matched pattern, or None."""
    for pat in _PLEONASM_PATTERNS:
        m = pat.search(text)
        if m is not None:
            return m.group(0)
    return None


def filter_10_pleonasm(row: dict) -> FilterResult:
    """Reject if the definition matches a known pleonasm pattern."""
    match = _pleonasm_match(row["definition"])
    if match is None:
        return FilterResult(action="accept", reason="")
    return FilterResult(
        action="reject",
        reason=f"pleonasm: matched closed-set pattern '{match}'",
    )
```

CRITICAL: before committing this list, diff the patterns above against
the current source-of-truth in `scripts/eval/validate_clue.py::_find_pleonasm`.
If the MLX lane has added a pattern since the closed-set in this plan
was written, copy that pattern over — the MLX-lane logbook is the
authority. The exact same set must match both lanes.

- [ ] **Step 6: Wire the new filters into the pipeline order**

The pipeline order in `run_pipeline.py` (created in Step 8 below) must
include filters 9 + 10 AFTER the existing filters 1-8 — they're the
last structural gates before the LLM-judge mock returns a verdict.

- [ ] **Step 7: Re-run the tests; verify all green**

```
python3 -m pytest scripts/clue_generation/pipeline_v2/ -v
```
Expected: all 27 original negative cases PASS; all 6+ new stem-leak +
pleonasm cases PASS; happy paths PASS.

If any of the original 27 cases now FAIL, the port broke a verbatim
filter — bisect and fix before continuing.

- [ ] **Step 8: Create run_pipeline.py**

```
cp /Users/isho/IdeaProjects/bliss-clue-ai/scripts/pipeline/run_pipeline.py \
   scripts/clue_generation/pipeline_v2/run_pipeline.py
```

Then edit the `PIPELINE_FILTERS` list to append filters 9 + 10:

```python
PIPELINE_FILTERS = [
    ("filter_1_typographiques", F.filter_1_typographiques, False),
    ("filter_2_caracteres_interdits", F.filter_2_caracteres_interdits, False),
    ("filter_3_longueur", F.filter_3_longueur, False),
    ("filter_4_stereotypes_ia", F.filter_4_stereotypes_ia, False),
    ("filter_5_auto_reference", F.filter_5_auto_reference, False),
    ("filter_6_langue_fr", F.filter_6_langue_fr, False),
    ("filter_7_tautologie", F.filter_7_tautologie, False),
    ("filter_8_llm_juge_mock", F.filter_8_llm_juge_mock, True),
    # === MLX-lane fusion (PR 3b) ===
    ("filter_9_stem_leak", F.filter_9_stem_leak, False),
    ("filter_10_pleonasm", F.filter_10_pleonasm, False),
]
```

Update the import block at the top of `run_pipeline.py` to import
from `..pipeline_v2` (relative to the script's own directory) per
the existing layout. Verify by running the CLI on the gold pilot:

```
python3 -m scripts.clue_generation.pipeline_v2.run_pipeline \
    --input data/curated/gold_pilot_v1.csv
```
Expected: 114 accept rows, 0 rejects, 0 warnings (per
`docs/pipeline_test_pilot_v1.md` from the source repo). If filter 9
or 10 trips on a gold pilot row, that's a genuine bug to investigate
— either the filter is mis-implemented or the gold row has a
pleonasm/stem-leak the source repo missed.

- [ ] **Step 9: Commit and PR**

```
git add scripts/clue_generation/pipeline_v2/
git commit -s -m "chore(clue-ai-modal): pipeline_v2 fuse stem-leak + pleonasm from MLX lane"
git push -u origin chore/clue-ai-modal-pipeline-v2-fusion
gh pr create --title "chore(clue-ai-modal): pipeline_v2 MLX fusion (PR 3b)" --body "..."
```

PR body cites spec §3.5.1 (the fusion table), confirms the 27-case
suite still passes, and includes the byte-level diff of the pleonasm
pattern set against `validate_clue.py::_find_pleonasm` to prove
parity.

---

## Task 7: PR 4a — prepare_dataset + manifest skeleton (chore)

**Wave:** 3. **Branch:** `chore/clue-ai-modal-prepare-dataset`.
**Depends on:** PR 2 (gold_pilot_v1.csv).

**Files:**
- Create: `scripts/clue_generation/modal/__init__.py` (empty)
- Create: `scripts/clue_generation/modal/prepare_dataset.py`
- Create: `scripts/clue_generation/modal/test_prepare_dataset.py`
- Create: `data/lora/modal_corpus_v1/manifest.toml` (skeleton; populated fully in PR 4b)
- Modify: `.gitignore` (add modal_corpus_v1 JSONL artefacts and gold-only smoke JSONL)

- [ ] **Step 1: Create the empty package marker**

```
mkdir -p scripts/clue_generation/modal
touch scripts/clue_generation/modal/__init__.py
mkdir -p data/lora/modal_corpus_v1
```

- [ ] **Step 2: Update .gitignore**

Add a section to `.gitignore`:
```
# Modal-lane corpus build artefacts (regeneratable from manifest + inputs)
data/lora/modal_corpus_v1/train.jsonl
data/lora/modal_corpus_v1/val.jsonl
data/seed/gold_pilot_v1_train.jsonl
data/seed/gold_pilot_v1_val.jsonl
```

- [ ] **Step 3: Write the failing stratification test**

Create `scripts/clue_generation/modal/test_prepare_dataset.py`:

```python
"""Tests for the single-source gold-only dataset prep path.

This is the simple smoke path that mirrors bliss-clue-ai's original
prepare_dataset.py: stratify by `force`, seed=42, val ratio 0.12.
The multi-source path lives in build_modal_corpus.py (PR 4b).
"""

from __future__ import annotations

import csv
import json
from pathlib import Path

import pytest

from . import prepare_dataset as pd


@pytest.fixture
def sample_rows() -> list[dict]:
    """Synthetic 20-row CSV with 4 forces, balanced."""
    rows: list[dict] = []
    for force in ("1", "2", "3", "4"):
        for i in range(5):
            rows.append({
                "mot": f"WORD{force}{i}",
                "definition": f"Definition {force}.{i}",
                "force": force,
                "pos": "nom_commun",
            })
    return rows


def test_split_stratifie_keeps_force_distribution(sample_rows):
    train, val = pd.split_stratifie(sample_rows)
    # round(5 * 0.12) = round(0.6) = 1 → 1 val per force, 4 train
    from collections import Counter
    val_forces = Counter(r["force"] for r in val)
    train_forces = Counter(r["force"] for r in train)
    for force in ("1", "2", "3", "4"):
        assert val_forces[force] == 1, f"force {force}: {val_forces}"
        assert train_forces[force] == 4, f"force {force}: {train_forces}"


def test_split_stratifie_is_reproducible(sample_rows):
    train_a, val_a = pd.split_stratifie(sample_rows)
    train_b, val_b = pd.split_stratifie(sample_rows)
    assert [r["mot"] for r in train_a] == [r["mot"] for r in train_b]
    assert [r["mot"] for r in val_a] == [r["mot"] for r in val_b]


def test_train_val_are_disjoint_and_exhaustive(sample_rows):
    train, val = pd.split_stratifie(sample_rows)
    train_mots = {r["mot"] for r in train}
    val_mots = {r["mot"] for r in val}
    all_mots = {r["mot"] for r in sample_rows}
    assert train_mots.isdisjoint(val_mots)
    assert train_mots | val_mots == all_mots


def test_construire_chat_entry_shape():
    row = {"mot": "POMME", "definition": "Tentation d’Ève"}
    entry = pd.construire_chat_entry(row)
    assert entry == {
        "messages": [
            {"role": "user", "content": "Donne une définition de mot fléché pour POMME."},
            {"role": "assistant", "content": "Tentation d’Ève"},
        ]
    }
```

Run it (test will fail because `prepare_dataset` doesn't exist yet):
```
python3 -m pytest scripts/clue_generation/modal/test_prepare_dataset.py -v
```
Expected: ImportError.

- [ ] **Step 4: Port prepare_dataset.py**

```
cp /Users/isho/IdeaProjects/bliss-clue-ai/scripts/training/prepare_dataset.py \
   scripts/clue_generation/modal/prepare_dataset.py
```

Update the path constants at the top of the file to point at bliss
paths:

```python
ROOT = Path(__file__).resolve().parents[2]  # bliss/ root
INPUT_CSV = ROOT / "data" / "curated" / "gold_pilot_v1.csv"
TRAIN_OUT = ROOT / "data" / "seed" / "gold_pilot_v1_train.jsonl"
VAL_OUT = ROOT / "data" / "seed" / "gold_pilot_v1_val.jsonl"
```

(The original used `data/seed/gold_pilot_v1.csv` — bliss puts the CSV
under `data/curated/`, but the generated JSONL still goes to
`data/seed/` to stay clear of `data/lora/modal_corpus_v1/` which
belongs to the multi-source builder in PR 4b.)

- [ ] **Step 5: Re-run tests; verify green**

```
python3 -m pytest scripts/clue_generation/modal/test_prepare_dataset.py -v
```
Expected: all 4 tests pass.

- [ ] **Step 6: Smoke-run on the real gold pilot**

```
python3 -m scripts.clue_generation.modal.prepare_dataset
```
Expected output: `Écrit : data/seed/gold_pilot_v1_train.jsonl (≈100 lignes)`
and `Écrit : data/seed/gold_pilot_v1_val.jsonl (≈14 lignes)`. Verify
the JSONL is well-formed:
```
wc -l data/seed/gold_pilot_v1_train.jsonl data/seed/gold_pilot_v1_val.jsonl
head -1 data/seed/gold_pilot_v1_train.jsonl | python3 -m json.tool
```

- [ ] **Step 7: Create the manifest skeleton**

Create `data/lora/modal_corpus_v1/manifest.toml`:

```toml
# Modal-lane SFT corpus manifest — v1
# Generated by scripts/clue_generation/modal/build_modal_corpus.py
# (PR 4b). PR 4a ships this skeleton; full implementation lands in 4b.
#
# Bumping any value below bumps the corpus version (v1 → v2). Each
# Modal training run records the manifest hash in its
# `model_version` tag so every adapter is traceable to an exact recipe.

version = "v1"
description = "Initial Modal-lane SFT corpus. Gold-weighted multi-source."
seed = 42
val_ratio = 0.12

# Held-out set: lemmas in this file MUST NOT appear in train.jsonl
# or val.jsonl. The builder enforces this as an assertion.
exclude_lemmas_from = "data/eval/eval_human.jsonl"

# Chat-Mistral template (assistant prompt is the clue, no metadata leakage).
user_prompt_template = "Donne une définition de mot fléché pour {mot}."

# Sources — see spec §2.2 for tier rationale. Order does not matter;
# the builder sorts deterministically before replication.
#
# Filter expressions support exactly this micro-grammar (parsed by
# scripts/clue_generation/modal/build_modal_corpus.py::_apply_row_filter):
#   filter   := clause ("and" clause)*
#   clause   := IDENT op operand
#   op       := "==" | "!="
#   operand  := IDENT | "'" STRING "'"
# Anything outside this grammar raises ValueError at build time.

[[sources]]
name = "gold_pilot_v1"
path = "data/curated/gold_pilot_v1.csv"
tier = "gold"
weight = 4
csv_delimiter = ";"
schema_mapping = { mot = "mot", definition = "definition", force = "force" }
row_filter = ""  # all rows

[[sources]]
name = "curated_fr"
path = "data/curated/fr.csv"
tier = "silver"
weight = 2
csv_delimiter = ","
schema_mapping = { mot = "word", definition = "clue", force = "" }
row_filter = "clue != ''"

[[sources]]
name = "curated_short_fr"
path = "data/curated/short-fr.csv"
tier = "silver"
weight = 2
csv_delimiter = ","
schema_mapping = { mot = "word", definition = "clue", force = "" }
row_filter = "clue != ''"

[[sources]]
name = "synthetic_clues"
path = "data/lora/synthetic_clues.csv"
tier = "silver"
weight = 2
csv_delimiter = ","
schema_mapping = { mot = "lemma", definition = "clue", force = "" }
row_filter = ""

[[sources]]
name = "rated_iters_y"
# Glob pattern; the builder expands and concatenates.
path_glob = "data/eval/lemma_clues_iter*.csv"
tier = "bronze"
weight = 1
csv_delimiter = ","
schema_mapping = { mot = "lemma", definition = "lemma_clue", force = "" }
row_filter = "rating == 'y'"  # human-judged correct only
```

This skeleton is committed in PR 4a but `build_modal_corpus.py` (the
script that reads it) lands in PR 4b. The skeleton is also the
contract — anyone touching the builder must keep this shape.

- [ ] **Step 8: Commit and PR**

```
git add scripts/clue_generation/modal/__init__.py \
        scripts/clue_generation/modal/prepare_dataset.py \
        scripts/clue_generation/modal/test_prepare_dataset.py \
        data/lora/modal_corpus_v1/manifest.toml \
        .gitignore
git commit -s -m "chore(clue-ai-modal): prepare_dataset.py + corpus manifest skeleton (PR 4a)"
git push -u origin chore/clue-ai-modal-prepare-dataset
gh pr create --title "chore(clue-ai-modal): single-source dataset prep + manifest skeleton (PR 4a)" --body "..."
```

---

## Task 8: PR 4b — build_modal_corpus.py (chore)

**Wave:** 4. **Branch:** `chore/clue-ai-modal-build-corpus`.
**Depends on:** PR 4a.

**Files:**
- Create: `scripts/clue_generation/modal/build_modal_corpus.py`
- Create: `scripts/clue_generation/modal/test_build_modal_corpus.py`
- Generated (committed): `data/lora/modal_corpus_v1/build_summary.md` (after first build)

### Note on row-filter grammar

`build_modal_corpus.py` evaluates the `row_filter` field of each
manifest source. **No `eval()` is used** — the builder ships a tiny
safe parser that handles exactly the grammar declared in the
manifest header (`==`, `!=`, string literals in single quotes,
column-name references, `and` joiner). Anything outside that
grammar raises `ValueError` at build time. The grammar covers every
filter the spec calls for (`rating == 'y'`, `clue != ''`); if a
future source needs richer logic, extend the parser explicitly
rather than reaching for `eval`.

- [ ] **Step 1: Write the failing test for the safe parser**

Create `scripts/clue_generation/modal/test_build_modal_corpus.py`:

```python
"""Tests for the multi-source manifest-driven corpus builder.

Properties under test:
  - Row-filter parser accepts exactly the manifest grammar and
    rejects anything else (no eval).
  - Each declared source loads with the manifest's CSV delimiter
    and schema mapping.
  - Row filter expressions are honoured (`rating == 'y'` etc.).
  - Weight replication produces exactly `weight × source_rows`
    rows per source in the concatenated training pool.
  - eval_human.jsonl lemmas are absent from train + val output.
  - Same manifest + same inputs → byte-identical JSONL output.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from . import build_modal_corpus as bc


# ---------- Row-filter parser unit tests -----------------------------

class TestRowFilterParser:
    def test_empty_filter_accepts_any_row(self):
        assert bc._apply_row_filter({"a": "x"}, "") is True

    def test_equality_match_passes(self):
        assert bc._apply_row_filter({"rating": "y"}, "rating == 'y'") is True

    def test_equality_mismatch_fails(self):
        assert bc._apply_row_filter({"rating": "n"}, "rating == 'y'") is False

    def test_inequality_match_passes(self):
        assert bc._apply_row_filter({"clue": "Definition"}, "clue != ''") is True

    def test_inequality_to_empty_string_fails_when_empty(self):
        assert bc._apply_row_filter({"clue": ""}, "clue != ''") is False

    def test_column_to_column_comparison(self):
        # "clue != word" should be False when clue == word
        assert bc._apply_row_filter({"word": "x", "clue": "x"}, "clue != word") is False
        assert bc._apply_row_filter({"word": "x", "clue": "y"}, "clue != word") is True

    def test_and_joiner(self):
        row = {"rating": "y", "clue": "Definition"}
        assert bc._apply_row_filter(row, "rating == 'y' and clue != ''") is True
        row2 = {"rating": "y", "clue": ""}
        assert bc._apply_row_filter(row2, "rating == 'y' and clue != ''") is False

    def test_unsupported_grammar_raises(self):
        with pytest.raises(ValueError, match="unsupported filter clause"):
            bc._apply_row_filter({"a": "x"}, "a == 'x' or a == 'y'")

    def test_unsupported_operator_raises(self):
        with pytest.raises(ValueError, match="unsupported filter clause"):
            bc._apply_row_filter({"a": "x"}, "a > 5")

    def test_missing_column_treated_as_empty_string(self):
        # Defensive: a column not in the row is "", which matches the CSV
        # DictReader default for missing fields after Python's csv module.
        assert bc._apply_row_filter({}, "rating == ''") is True


# ---------- Builder integration tests --------------------------------

@pytest.fixture
def tmp_corpus_dir(tmp_path: Path) -> Path:
    """Synthetic corpus inputs + manifest, scoped per test."""
    root = tmp_path
    (root / "data" / "curated").mkdir(parents=True)
    (root / "data" / "lora" / "modal_corpus_v1").mkdir(parents=True)
    (root / "data" / "eval").mkdir(parents=True)

    # gold source (3 rows, force-tagged)
    (root / "data" / "curated" / "gold.csv").write_text(
        "mot;definition;force\n"
        "POMME;Tentation d'Ève;1\n"
        "COQ;Mâle de la basse-cour;2\n"
        "AÏ;Paresseux;3\n",
        encoding="utf-8",
    )

    # silver source (2 rows, no force)
    (root / "data" / "curated" / "silver.csv").write_text(
        "word,clue\n"
        "lait,Boisson blanche\n"
        "pain,Aliment de boulangerie\n",
        encoding="utf-8",
    )

    # held-out set (1 lemma)
    (root / "data" / "eval" / "eval_human.jsonl").write_text(
        '{"lemma": "POMME"}\n',
        encoding="utf-8",
    )

    # manifest
    (root / "data" / "lora" / "modal_corpus_v1" / "manifest.toml").write_text(
        """
version = "test"
seed = 42
val_ratio = 0.0
exclude_lemmas_from = "data/eval/eval_human.jsonl"
user_prompt_template = "Donne une définition de mot fléché pour {mot}."

[[sources]]
name = "gold"
path = "data/curated/gold.csv"
tier = "gold"
weight = 4
csv_delimiter = ";"
schema_mapping = { mot = "mot", definition = "definition", force = "force" }
row_filter = ""

[[sources]]
name = "silver"
path = "data/curated/silver.csv"
tier = "silver"
weight = 2
csv_delimiter = ","
schema_mapping = { mot = "word", definition = "clue", force = "" }
row_filter = ""
""",
        encoding="utf-8",
    )

    return root


def test_loads_sources_with_their_delimiter_and_schema(tmp_corpus_dir):
    rows = bc.load_all_sources(
        tmp_corpus_dir,
        tmp_corpus_dir / "data" / "lora" / "modal_corpus_v1" / "manifest.toml",
    )
    # 2 surviving gold rows × weight 4 = 8 (POMME excluded by held-out)
    # + 2 silver rows × weight 2 = 4 → total 12
    assert len(rows) == 12, [r["mot"] for r in rows]
    assert any(r["mot"] == "lait" and r["definition"] == "Boisson blanche" for r in rows)


def test_excludes_held_out_lemmas(tmp_corpus_dir):
    rows = bc.load_all_sources(
        tmp_corpus_dir,
        tmp_corpus_dir / "data" / "lora" / "modal_corpus_v1" / "manifest.toml",
    )
    assert not any(r["mot"] == "POMME" for r in rows), \
        "POMME is in eval_human.jsonl and must not appear in the corpus"


def test_weight_replication_exact_counts(tmp_corpus_dir):
    rows = bc.load_all_sources(
        tmp_corpus_dir,
        tmp_corpus_dir / "data" / "lora" / "modal_corpus_v1" / "manifest.toml",
    )
    from collections import Counter
    mot_counts = Counter(r["mot"] for r in rows)
    assert mot_counts["COQ"] == 4   # 1 row × weight 4
    assert mot_counts["AÏ"] == 4
    assert mot_counts["lait"] == 2  # 1 row × weight 2
    assert mot_counts["pain"] == 2


def test_rebuild_is_byte_identical(tmp_corpus_dir):
    out_dir = tmp_corpus_dir / "data" / "lora" / "modal_corpus_v1"
    bc.build_corpus(tmp_corpus_dir, out_dir / "manifest.toml", out_dir)
    train_a = (out_dir / "train.jsonl").read_bytes()
    val_a = (out_dir / "val.jsonl").read_bytes()
    (out_dir / "train.jsonl").unlink()
    (out_dir / "val.jsonl").unlink()
    bc.build_corpus(tmp_corpus_dir, out_dir / "manifest.toml", out_dir)
    train_b = (out_dir / "train.jsonl").read_bytes()
    val_b = (out_dir / "val.jsonl").read_bytes()
    assert train_a == train_b
    assert val_a == val_b


def test_held_out_assertion_fires_when_leak_detected(tmp_corpus_dir, monkeypatch):
    """If the exclude logic regresses, the output assertion catches it."""
    out_dir = tmp_corpus_dir / "data" / "lora" / "modal_corpus_v1"
    monkeypatch.setattr(bc, "_load_held_out_lemmas", lambda *a, **k: set())
    with pytest.raises(AssertionError, match="held-out lemma"):
        bc.build_corpus(tmp_corpus_dir, out_dir / "manifest.toml", out_dir)
```

Run:
```
python3 -m pytest scripts/clue_generation/modal/test_build_modal_corpus.py -v
```
Expected: ImportError on `build_modal_corpus` (red — not written yet).

- [ ] **Step 2: Write build_modal_corpus.py**

Create `scripts/clue_generation/modal/build_modal_corpus.py`:

```python
"""Multi-source, manifest-driven Modal-lane SFT corpus builder.

Reads data/lora/modal_corpus_v1/manifest.toml and produces:
  - data/lora/modal_corpus_v1/train.jsonl     (gitignored)
  - data/lora/modal_corpus_v1/val.jsonl       (gitignored)
  - data/lora/modal_corpus_v1/build_summary.md  (committed)

Per-source weights, schema mapping, and held-out exclusion are
declared in the manifest. See docs/superpowers/specs/2026-05-25-
clue-ai-modal-migration-design.md §3.7 for design rationale.
"""

from __future__ import annotations

import csv
import json
import random
import re
import sys
import tomllib
from collections import Counter, defaultdict
from glob import glob
from pathlib import Path
from typing import Any


# ---------- Row-filter mini-language (NO eval) -----------------------

# Grammar (single clause regex; we split on ' and ' for the joiner):
#   clause := IDENT (==|!=) (IDENT | '<literal>')
_CLAUSE_RE = re.compile(
    r"""^\s*
        (?P<lhs>[A-Za-z_][A-Za-z0-9_]*)
        \s*(?P<op>==|!=)\s*
        (?:
            '(?P<str>[^']*)'                # single-quoted string literal
          | (?P<rhs_col>[A-Za-z_][A-Za-z0-9_]*)
        )
        \s*$""",
    re.VERBOSE,
)


def _apply_row_filter(row: dict, expr: str) -> bool:
    """Evaluate the manifest's restricted filter grammar.

    Supports only equality/inequality comparisons between columns
    and literal strings, joined by ``and``. Anything else is a
    ``ValueError`` — no fall-through to ``eval``.
    """
    expr = expr.strip()
    if not expr:
        return True
    for clause in (c.strip() for c in expr.split(" and ")):
        m = _CLAUSE_RE.match(clause)
        if m is None:
            raise ValueError(f"unsupported filter clause: {clause!r}")
        lhs_val = (row.get(m["lhs"]) or "")
        if m["str"] is not None:
            rhs_val = m["str"]
        else:
            rhs_val = (row.get(m["rhs_col"]) or "")
        op = m["op"]
        if op == "==" and lhs_val != rhs_val:
            return False
        if op == "!=" and lhs_val == rhs_val:
            return False
    return True


# ---------- Manifest ------------------------------------------------

def _load_manifest(manifest_path: Path) -> dict[str, Any]:
    with manifest_path.open("rb") as f:
        return tomllib.load(f)


def _resolve_path(root: Path, relpath: str) -> Path:
    return root / relpath


# ---------- Held-out set --------------------------------------------

def _load_held_out_lemmas(root: Path, path: str) -> set[str]:
    """Read eval_human.jsonl (or similar) and return lemmas as a set."""
    if not path:
        return set()
    p = _resolve_path(root, path)
    if not p.exists():
        return set()
    out: set[str] = set()
    with p.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            lemma = obj.get("lemma") or obj.get("mot") or obj.get("word")
            if lemma:
                out.add(lemma.lower())
    return out


# ---------- Source loading ------------------------------------------

def _load_source(root: Path, src: dict[str, Any]) -> list[dict[str, str]]:
    """Load one source per its manifest entry and apply schema mapping."""
    if "path_glob" in src:
        paths = [Path(p) for p in glob(str(root / src["path_glob"]))]
    else:
        paths = [_resolve_path(root, src["path"])]

    delim = src.get("csv_delimiter", ",")
    mapping = src["schema_mapping"]
    row_filter = src.get("row_filter", "")
    name = src["name"]

    out: list[dict[str, str]] = []
    for p in paths:
        if not p.exists():
            raise FileNotFoundError(f"source '{name}': {p} not found")
        with p.open(encoding="utf-8", newline="") as f:
            reader = csv.DictReader(f, delimiter=delim)
            for row in reader:
                if not _apply_row_filter(row, row_filter):
                    continue
                mot_col = mapping["mot"]
                def_col = mapping["definition"]
                force_col = mapping.get("force", "")
                mot = (row.get(mot_col) or "").strip()
                definition = (row.get(def_col) or "").strip()
                if not mot or not definition:
                    continue
                out.append({
                    "mot": mot,
                    "definition": definition,
                    "force": (row.get(force_col) or "").strip() if force_col else "",
                    "_source": name,
                })
    return out


def load_all_sources(root: Path, manifest_path: Path) -> list[dict[str, str]]:
    """Load every source, apply held-out exclusion, replicate by weight."""
    manifest = _load_manifest(manifest_path)
    held_out = _load_held_out_lemmas(root, manifest.get("exclude_lemmas_from", ""))
    all_rows: list[dict[str, str]] = []
    for src in manifest["sources"]:
        rows = _load_source(root, src)
        rows = [r for r in rows if r["mot"].lower() not in held_out]
        weight = int(src["weight"])
        all_rows.extend(rows * weight)
    return all_rows


# ---------- Chat-Mistral formatting --------------------------------

def _to_chat_entry(row: dict[str, str], template: str) -> dict:
    return {
        "messages": [
            {"role": "user", "content": template.format(mot=row["mot"])},
            {"role": "assistant", "content": row["definition"]},
        ]
    }


# ---------- Split ---------------------------------------------------

def _split_train_val(
    rows: list[dict[str, str]],
    val_ratio: float,
    seed: int,
) -> tuple[list[dict], list[dict]]:
    """Stratify by force where present; merge unstratified rows."""
    rng = random.Random(seed)
    by_force: dict[str, list[dict]] = defaultdict(list)
    for r in rows:
        by_force[r.get("force", "")].append(r)
    train: list[dict] = []
    val: list[dict] = []
    for force in sorted(by_force.keys()):
        group = list(by_force[force])
        rng.shuffle(group)
        n_val = round(len(group) * val_ratio)
        val.extend(group[:n_val])
        train.extend(group[n_val:])
    rng.shuffle(train)
    rng.shuffle(val)
    return train, val


# ---------- Build ---------------------------------------------------

def build_corpus(root: Path, manifest_path: Path, out_dir: Path) -> dict:
    manifest = _load_manifest(manifest_path)
    rows = load_all_sources(root, manifest_path)

    # Defense-in-depth held-out assertion (load_all_sources already filters).
    held_out = _load_held_out_lemmas(root, manifest.get("exclude_lemmas_from", ""))
    for r in rows:
        assert r["mot"].lower() not in held_out, \
            f"held-out lemma leaked into corpus: {r['mot']}"

    train_rows, val_rows = _split_train_val(
        rows,
        val_ratio=float(manifest.get("val_ratio", 0.12)),
        seed=int(manifest.get("seed", 42)),
    )

    template = manifest["user_prompt_template"]
    out_dir.mkdir(parents=True, exist_ok=True)

    with (out_dir / "train.jsonl").open("w", encoding="utf-8") as f:
        for r in train_rows:
            f.write(json.dumps(_to_chat_entry(r, template), ensure_ascii=False) + "\n")
    with (out_dir / "val.jsonl").open("w", encoding="utf-8") as f:
        for r in val_rows:
            f.write(json.dumps(_to_chat_entry(r, template), ensure_ascii=False) + "\n")

    summary = _build_summary(manifest, rows, train_rows, val_rows)
    (out_dir / "build_summary.md").write_text(summary, encoding="utf-8")

    return {
        "train_size": len(train_rows),
        "val_size": len(val_rows),
        "total_with_weights": len(rows),
    }


def _build_summary(manifest, rows, train, val) -> str:
    from datetime import date
    lines = [
        f"# Modal corpus build summary — {manifest['version']}",
        "",
        f"- Build date (local): {date.today().isoformat()}",
        f"- Seed: {manifest.get('seed', 42)}",
        f"- Val ratio: {manifest.get('val_ratio', 0.12)}",
        f"- Total rows after weighting + exclusion: {len(rows)}",
        f"- Train rows: {len(train)} | Val rows: {len(val)}",
        "",
        "## Rows per source (after held-out exclusion, before weight replication)",
        "",
        "| Source | Tier | Weight | Rows in | Rows out (× weight) |",
        "|---|---|---:|---:|---:|",
    ]
    src_counts = Counter(r["_source"] for r in rows)
    for src in manifest["sources"]:
        name = src["name"]
        weight = int(src["weight"])
        out = src_counts.get(name, 0)
        rows_in = out // weight if weight > 0 else 0
        lines.append(
            f"| `{name}` | {src['tier']} | {weight} | {rows_in} | {out} |"
        )
    return "\n".join(lines) + "\n"


def main(argv: list[str]) -> int:
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest",
                        default="data/lora/modal_corpus_v1/manifest.toml")
    parser.add_argument("--out-dir",
                        default="data/lora/modal_corpus_v1")
    args = parser.parse_args(argv)

    root = Path(__file__).resolve().parents[3]  # bliss/ root
    manifest_path = root / args.manifest
    out_dir = root / args.out_dir

    summary = build_corpus(root, manifest_path, out_dir)
    print(f"Built corpus: {summary}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```

- [ ] **Step 3: Run tests; verify green**

```
python3 -m pytest scripts/clue_generation/modal/test_build_modal_corpus.py -v
```
Expected: all 15+ tests PASS (10 parser tests + 5 builder tests). If
the `test_excludes_held_out_lemmas` test fails, debug the
`_load_held_out_lemmas` path resolution and the lemma normalisation
(case-insensitive match).

- [ ] **Step 4: Run the builder against the real manifest**

```
python3 -m scripts.clue_generation.modal.build_modal_corpus
```
Expected: prints `Built corpus: {'train_size': ..., 'val_size': ...}`.
Inspect:
```
cat data/lora/modal_corpus_v1/build_summary.md
wc -l data/lora/modal_corpus_v1/train.jsonl data/lora/modal_corpus_v1/val.jsonl
```

Sanity check: total rows ≈ (114 gold × 4) + (63 fr × 2) + (239
short-fr × 2) + (400 synthetic × 2) + (sum of y-rated × 1) ≈
2000-3000. Train/val split ≈ 88/12 of that.

- [ ] **Step 5: Commit the build_summary.md (one-time)**

```
git add scripts/clue_generation/modal/build_modal_corpus.py \
        scripts/clue_generation/modal/test_build_modal_corpus.py \
        data/lora/modal_corpus_v1/build_summary.md
git commit -s -m "chore(clue-ai-modal): manifest-driven multi-source corpus builder (PR 4b)"
git push -u origin chore/clue-ai-modal-build-corpus
gh pr create --title "chore(clue-ai-modal): multi-source corpus builder (PR 4b)" --body "..."
```

PR body cites spec §3.7 (corpus fusion strategy), explicitly notes
"No `eval()` — see `_apply_row_filter` micro-parser; grammar is the
exact subset the manifest declares", and includes a copy-paste of
the `build_summary.md` row counts.

---

## Task 9: PR 6 — Modal paliers 3a + 3b (chore)

**Wave:** 5. **Branch:** `chore/clue-ai-modal-paliers-3a-3b`.
**Depends on:** PR 4b (corpus JSONL exists), PR 5 (Modal infra paliers 0-2).

**Files:**
- Create: `modal_jobs/03a_upload_dataset.py`
- Create: `modal_jobs/03b_finetune.py`

This PR is ~760 lines (145 + 615). **Cap override pre-flagged at
dispatch** per [[feedback-cap-override-short-circuit]].

- [ ] **Step 1: Port palier 3a (upload dataset)**

```
cp /Users/isho/IdeaProjects/bliss-clue-ai/modal_jobs/03a_upload_dataset.py \
   modal_jobs/03a_upload_dataset.py
```

Edit the path constants to read from the new corpus location:

```python
ROOT = Path(__file__).resolve().parent.parent  # bliss/ root
TRAIN_PATH = ROOT / "data" / "lora" / "modal_corpus_v1" / "train.jsonl"
VAL_PATH = ROOT / "data" / "lora" / "modal_corpus_v1" / "val.jsonl"
```

(The original used `data/seed/gold_pilot_v1_{train,val}.jsonl`; for
the fused corpus we point at `data/lora/modal_corpus_v1/`. To keep
the gold-only smoke path available, accept `--mode {fused, gold-only}`
on the local entrypoint.)

Add at the top of `main()`:
```python
import argparse
parser = argparse.ArgumentParser()
parser.add_argument("--mode", choices=["fused", "gold-only"], default="fused")
args, _ = parser.parse_known_args()
if args.mode == "gold-only":
    train_path = ROOT / "data" / "seed" / "gold_pilot_v1_train.jsonl"
    val_path = ROOT / "data" / "seed" / "gold_pilot_v1_val.jsonl"
else:
    train_path = TRAIN_PATH
    val_path = VAL_PATH
```

The remote function still writes `/datasets/train.jsonl` and
`/datasets/val.jsonl` regardless of mode — the Modal volume layout
is stable so palier 3b doesn't need to know which corpus is loaded.

- [ ] **Step 2: Port palier 3b (fine-tune)**

```
cp /Users/isho/IdeaProjects/bliss-clue-ai/modal_jobs/03b_finetune.py \
   modal_jobs/03b_finetune.py
```

Edit the dataset path constants in the `ÉTAPE C — Chargement datasets`
section:

```python
dataset = load_dataset(
    "json",
    data_files={
        "train": "/datasets/train.jsonl",      # was gold_pilot_v1_train.jsonl
        "validation": "/datasets/val.jsonl",   # was gold_pilot_v1_val.jsonl
    },
)
```

These names match what `03a_upload_dataset.py` writes — the file
shapes are the same, only paths changed.

Bump the adapter version label so the fused-corpus run is
distinguishable from the bliss-clue-ai pilot:

```python
adapter_path = "/adapters/mistral-nemo-pilot-v1"  # was mots-fleches-pilot-v2
```

This adapter version matches the `mistral-nemo-pilot-vN` source
namespace declared in the spec (§3.3).

- [ ] **Step 3: Static syntax check**

```
python3 -m py_compile modal_jobs/03a_upload_dataset.py modal_jobs/03b_finetune.py
```
Expected: no errors.

- [ ] **Step 4: Update README.md to reflect the fused-corpus default**

Edit `modal_jobs/README.md` (created in PR 5) — under the paliers
table, append a note:

```
**Palier 3a default mode** is `--mode fused` (reads
`data/lora/modal_corpus_v1/train.jsonl + val.jsonl`). For a smoke
test against the gold pilot alone, pass `--mode gold-only` which
reads `data/seed/gold_pilot_v1_train.jsonl + val.jsonl` (output of
`scripts/clue_generation/modal/prepare_dataset.py`).
```

This edit is included in PR 6 (not PR 5) because it documents
behaviour that lands here.

- [ ] **Step 5: Manual smoke test (developer-local)**

After PR 6 merges, the maintainer runs:
```
# Build the fused corpus locally
python3 -m scripts.clue_generation.modal.build_modal_corpus

# Upload to Modal volume
modal run modal_jobs/03a_upload_dataset.py

# Run the pilot fine-tune (~25 min, ~$1.50)
modal run modal_jobs/03b_finetune.py
```

Expected from 03b:
- Training loss decreases from ~3.0 toward ~1.0 over 5 epochs.
- Eval losses per epoch logged; best-val-loss epoch noted.
- Adapter saved to `/adapters/mistral-nemo-pilot-v1`.
- Visual eval prints 5 val rows with `Gold` vs `Généré` for spot-check.

Document the actual training loss, eval losses, and 5 visual examples
in the PR body (under "Test plan").

- [ ] **Step 6: Commit and PR**

```
git add modal_jobs/03a_upload_dataset.py \
        modal_jobs/03b_finetune.py \
        modal_jobs/README.md
git commit -s -m "chore(clue-ai-modal): port Modal paliers 3a + 3b (fine-tune on fused corpus)"
git push -u origin chore/clue-ai-modal-paliers-3a-3b
gh pr create --title "chore(clue-ai-modal): Modal paliers 3a + 3b (fine-tune on fused corpus)" --body "..."
```

PR body invokes the standing cap override: "Pre-flagged: 03b_finetune.py
is ~615 lines of a single Modal function (training pipeline); splitting
it would force reviewers to jump between trainer config, attention
diagnostic, and training loop. Reviewing the file as one unit is the
right shape."

PR body also includes the pilot run's actual cost ($X.XX), runtime
(N min), and visual eval table (5 val rows × gold vs generated).

---

## Task 10: PR 7 — Modal → CSV bridge (chore)

**Wave:** 6. **Branch:** `chore/clue-ai-modal-export-bridge`.
**Depends on:** PR 3b (pipeline_v2 fused validator), PR 6 (adapter exists).

**Files:**
- Create: `scripts/clue_generation/modal/export_adapter_to_csv.py`
- Create: `scripts/clue_generation/modal/test_export_adapter_to_csv.py`

This script downloads an adapter from the Modal volume, runs inference
on a target lemma list, validates each generated clue through
`pipeline_v2`, and writes a CSV in the shape that
`bliss-worker ingest-clue-candidates` expects:
`lemma,clue_text,source,model_version,confidence`.

- [ ] **Step 1: Write the failing test for CSV-shaping (Modal mocked at boundary)**

Create `scripts/clue_generation/modal/test_export_adapter_to_csv.py`:

```python
"""Tests for the Modal → CSV bridge.

Modal-side inference is mocked at the boundary per CLAUDE.md
("Mock only at external boundaries"). Pipeline_v2 validation is
exercised end-to-end on the in-memory generated clues.
"""

from __future__ import annotations

import csv
from pathlib import Path

import pytest

from . import export_adapter_to_csv as ex


def test_csv_has_required_columns(tmp_path, monkeypatch):
    """Output CSV must have exactly the columns bliss-worker expects."""
    monkeypatch.setattr(
        ex,
        "_generate_clues_on_modal",
        lambda lemmas, adapter_path, **kw: [
            {"lemma": lem, "clue": f"Définition de {lem}", "confidence": 0.9}
            for lem in lemmas
        ],
    )
    monkeypatch.setattr(
        ex,
        "_validate_clue",
        lambda clue, lemma: {"status": "accept", "flag": "ok"},
    )

    out_path = tmp_path / "out.csv"
    ex.run(
        lemma_list=["chat", "chien", "oiseau"],
        adapter_path="/adapters/mistral-nemo-pilot-v1",
        model_version="mistral-nemo-pilot-v1",
        source_tag="mistral-nemo-pilot-v1",
        out_path=out_path,
    )
    with out_path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        assert reader.fieldnames == [
            "lemma", "clue_text", "source", "model_version", "confidence",
        ]
        rows = list(reader)
    assert len(rows) == 3
    assert all(r["source"] == "mistral-nemo-pilot-v1" for r in rows)
    assert all(r["model_version"] == "mistral-nemo-pilot-v1" for r in rows)


def test_rejects_dropped_by_pipeline_v2_are_excluded(tmp_path, monkeypatch):
    monkeypatch.setattr(
        ex,
        "_generate_clues_on_modal",
        lambda lemmas, adapter_path, **kw: [
            {"lemma": "chat", "clue": "Félin domestique courant", "confidence": 0.9},
            {"lemma": "PROTECTION", "clue": "Protéger un objet", "confidence": 0.8},
        ],
    )
    # Real pipeline_v2 should reject the stem-leak case on PROTECTION
    out_path = tmp_path / "out.csv"
    ex.run(
        lemma_list=["chat", "PROTECTION"],
        adapter_path="/adapters/mistral-nemo-pilot-v1",
        model_version="mistral-nemo-pilot-v1",
        source_tag="mistral-nemo-pilot-v1",
        out_path=out_path,
    )
    with out_path.open(encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    lemmas = {r["lemma"] for r in rows}
    assert "chat" in lemmas
    assert "PROTECTION" not in lemmas, \
        "stem-leak case must be filtered by pipeline_v2 filter_9"
```

Run:
```
python3 -m pytest scripts/clue_generation/modal/test_export_adapter_to_csv.py -v
```
Expected: ImportError (red).

- [ ] **Step 2: Write export_adapter_to_csv.py**

Create `scripts/clue_generation/modal/export_adapter_to_csv.py`:

```python
"""Modal adapter → clue_candidates CSV bridge.

Workflow:
  1. Take a lemma list (typically the surface words in
     grid/api/src/main/resources/words/words-fr.csv that need clues).
  2. Run Modal-side inference: for each lemma, generate K candidates
     at varied temperature, take the best by confidence.
  3. Validate each generated clue through pipeline_v2 (filters 1-10
     + normalizers). Drop rejects.
  4. Write CSV in the shape bliss-worker ingest-clue-candidates
     expects: lemma,clue_text,source,model_version,confidence.

The Modal-side inference call is the only place Modal is touched
from this script; the rest is pure local logic. See ADR-0057 for
the rationale on keeping training+inference on Modal but production
read path local.
"""

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
    """Invoke the Modal-side inference function. Returns one dict per
    (lemma, candidate) with keys `lemma`, `clue`, `confidence`.

    This function is the BOUNDARY between local CSV logic and the
    Modal cloud — mock it in tests. The Modal app + function lives
    in modal_jobs/04_infer.py (follow-up palier).
    """
    raise NotImplementedError(
        "Wire to Modal inference function in a follow-up palier 4."
    )


def _validate_clue(clue: str, lemma: str) -> dict[str, Any]:
    """Run pipeline_v2 against a single clue."""
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
    """Generate, validate, write CSV. Return counts."""
    raw = _generate_clues_on_modal(lemma_list, adapter_path)
    by_lemma: dict[str, list[dict[str, Any]]] = {}
    for cand in raw:
        by_lemma.setdefault(cand["lemma"], []).append(cand)
    accepted: list[dict[str, Any]] = []
    dropped = 0
    for lemma, cands in by_lemma.items():
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
    """One-lemma-per-line text file. Strips empties + comments."""
    with path.open(encoding="utf-8") as f:
        return [
            line.strip() for line in f
            if line.strip() and not line.startswith("#")
        ]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adapter-path", required=True,
                        help="Modal volume path, e.g. /adapters/mistral-nemo-pilot-v1")
    parser.add_argument("--model-version", required=True,
                        help="Records to clue_candidates.model_version")
    parser.add_argument("--source-tag", required=True,
                        help="Records to clue_candidates.source")
    parser.add_argument("--lemma-list", type=Path, required=True,
                        help="Text file with one lemma per line")
    parser.add_argument("--out", type=Path, default=None,
                        help="Output CSV path; defaults to data/modal_exports/<source-tag>.csv")
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
```

NOTE: `_generate_clues_on_modal` is a `NotImplementedError` stub in
this PR. The actual Modal-side inference call requires standing up a
fourth Modal app + a tiny `04_infer.py` palier; that's a follow-up
workstream after the pilot training run validates the adapter exists
and visual-eval looks reasonable. Document this clearly in the PR
body so reviewers know the bridge is *wired through* but not yet
*plugged in*.

- [ ] **Step 3: Run tests; verify green**

```
python3 -m pytest scripts/clue_generation/modal/test_export_adapter_to_csv.py -v
```
Expected: both tests PASS (Modal call mocked).

- [ ] **Step 4: Commit and PR**

```
git add scripts/clue_generation/modal/export_adapter_to_csv.py \
        scripts/clue_generation/modal/test_export_adapter_to_csv.py
git commit -s -m "chore(clue-ai-modal): bridge script for adapter→CSV (PR 7)"
git push -u origin chore/clue-ai-modal-export-bridge
gh pr create --title "chore(clue-ai-modal): Modal adapter → clue_candidates CSV bridge (PR 7)" --body "..."
```

PR body cites spec §4 (data flow) and explicitly notes the Modal
inference stub: "The bridge wires together lemma list → Modal
inference → pipeline_v2 → CSV. The Modal inference call is a
`NotImplementedError` stub; a follow-up palier 4 will stand up the
inference Modal app. The CSV-shaping + pipeline_v2 path is fully
tested."

---

## Task 11: PR 8 — Skill update + pilot validation report (docs)

**Wave:** 7. **Branch:** `docs/clue-ai-modal-skill-update`.
**Depends on:** PR 7 (the bridge exists, both lanes documented end-to-end).

**Files:**
- Modify: `.claude/skills/clue-ai/SKILL.md`
- Create: `docs/eval/pipeline_v2_pilot_validation.md`

- [ ] **Step 1: Port the pilot validation report**

```
cp /Users/isho/IdeaProjects/bliss-clue-ai/docs/pipeline_test_pilot_v1.md \
   docs/eval/pipeline_v2_pilot_validation.md
```

Then prepend a frontmatter note:
```markdown
> **Lane scope:** Pipeline_v2 calibration report for the Modal lane
> gold pilot v1 corpus, ported from bliss-clue-ai's
> `docs/pipeline_test_pilot_v1.md`. This logbook entry sits alongside
> the MLX-lane logbook `docs/eval/clue-gen-v0.md`; the two are
> independent but the MLX-lane stem-leak / pleonasm cases that
> motivated pipeline_v2 filters 9 + 10 cross-reference there.
```

If the maintainer's pilot run (PR 6) produced an updated calibration
(e.g. additional rejects), append a "## §9. Calibration update after
bliss pilot run" section with the new numbers.

- [ ] **Step 2: Update the clue-ai skill**

Open `.claude/skills/clue-ai/SKILL.md`. After the existing "Pipeline
at a glance" diagram (which describes the MLX lane), insert a new
section:

```markdown
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
| 7 | `python3 -m scripts.clue_generation.modal.export_adapter_to_csv …`               | $0.20  | pipeline_v2 + CSV in clue_candidates shape (when palier 4 inference Modal app lands) |
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
- **Don't** add `eval()` to the manifest row-filter — the
  micro-parser at `build_modal_corpus.py::_apply_row_filter` is
  intentional, extend it explicitly if the grammar needs to grow.
```

- [ ] **Step 3: Verify the skill still reads cleanly**

Run:
```
wc -l .claude/skills/clue-ai/SKILL.md
```
Expected: a few hundred lines longer than before. Spot-check by
reading the new section in-place.

- [ ] **Step 4: Commit and PR**

```
git add .claude/skills/clue-ai/SKILL.md \
        docs/eval/pipeline_v2_pilot_validation.md
git commit -s -m "docs(clue-ai-modal): skill + pilot validation report describe both lanes (PR 8)"
git push -u origin docs/clue-ai-modal-skill-update
gh pr create --title "docs(clue-ai-modal): skill update for two-lane reality (PR 8)" --body "..."
```

PR body lists the surface added (Modal-lane section + runbook) and
includes the spec §10 (definition of done) checklist with each item
ticked.

---

## Cross-cutting checklist (run before declaring the workstream done)

- [ ] All 11 PRs (PR 0 → PR 8 with 3a/3b and 4a/4b) merged on `main`.
- [ ] ADR-0057 has `Status: Accepted`.
- [ ] `data/lora/modal_corpus_v1/manifest.toml` is committed; running
      `python3 -m scripts.clue_generation.modal.build_modal_corpus`
      produces a fresh `build_summary.md` that diff-matches the
      committed one (same inputs + manifest = same summary).
- [ ] `python3 -m pytest scripts/clue_generation/pipeline_v2/ -v`
      shows all 27 negative cases from `bliss-clue-ai` plus ≥10
      additional stem-leak / pleonasm cases passing.
- [ ] `python3 -m pytest scripts/clue_generation/modal/ -v` shows
      stratification reproducibility, schema mapping, weight
      replication, held-out exclusion, and the row-filter parser
      all green.
- [ ] At least one end-to-end Modal run has completed (palier 3b
      against the fused corpus); adapter exists on `mots-fleches-adapters`
      volume; visual eval results are recorded in PR 6's body or in
      `docs/eval/pipeline_v2_pilot_validation.md`.
- [ ] `pytest scripts/eval/` is green — the MLX-lane runtime guard
      `test_runtime_csv_pleonasms.py` still passes against the
      unchanged committed `words-fr.csv`. (CRITICAL — proves this
      migration didn't disturb the production read path.)
- [ ] `.claude/skills/clue-ai/SKILL.md` describes both lanes
      end-to-end and an agent following the skill can run either
      pipeline from the runbook.

---

## What the cron orchestrator runs (if /orchestrate is invoked next)

This plan is dispatch-ready. The wave map above feeds directly into
the dispatch skill (per [[reference-dispatch-skill]]). When the user
runs `/orchestrate clue-ai-modal`, the orchestrator:

1. Reads this plan + the spec.
2. Generates `docs/superpowers/plans/2026-05-25-clue-ai-modal-migration-orchestration-procedure.md`
   and `…-orchestration-log.md` per [[reference-orchestration-procedure]].
3. Dispatches Wave 1 (PR 0 / ADR-0057) to a single implementer subagent.
4. After PR 0 merges, fans out Wave 2 (PR 1 + PR 2 + PR 5) to three
   parallel implementer subagents.
5. Continues per the wave map; 2-minute cron polling per
   [[feedback-cron-orchestration-cadence]]; `claude-review` IN_PROGRESS
   triggers wait, not skip.
6. The standing cap override is pre-flagged at dispatch for PR 5,
   PR 3a, and PR 6 per [[feedback-cap-override-short-circuit]] so the
   auto-fixer doesn't waste a cycle trying to split them.

No part of this plan is gated on the parallel survey orchestrator —
they own `feat/survey-*` + ADR-0056 + `survey/**` files; we own
`*/clue-ai-modal-*` + ADR-0057 + the file surfaces listed in §"File
Structure" above. No collisions.
