# Clue training loop — RAFT round-0/round-1 tooling (2026-05-26)

> **For agentic workers:** REQUIRED SUB-SKILL: invoke `dispatch` at session start. This plan is the canonical wave map for the clue-loop RAFT tooling workstream. Steps use checkbox (`- [ ]`) syntax for tracking. PRs are independent and dispatchable in parallel.

## Goal

Stand up the iterative clue-generation training loop on Modal. Round-0 SFT on the existing gold pilot v1 → generate N candidates → solo-rate via `/sondage` → keep `verdict=GOOD` ratings as new SFT examples → re-train (RAFT). Repeat.

**Algorithm choice — RAFT, not DPO.** DPO assumes a competent base; with ~100 gold rows the round-0 adapter is underspecified, and DPO is known to be unstable below ~500 preference pairs. RAFT (rejection-sampling SFT) re-uses the existing `03b_finetune.py` machinery, accepts a binary signal, and grows the training set every cycle. ADR-0057's deferred DPO hyperparameters stay deferred until ≥500 accumulated preferences justify the algorithm shift.

## Honest expectation-setting

Round-0 SFT on ~100 gold rows produces a narrow adapter that learned the gold style but does not generalize broadly. Round-1 generation will be biased toward gold tone; the validator may drop 60-70% (vs the usual 30-50%). Round-1 RAFT-with-winners is **directional**, not absolute — the loop's value is the *infrastructure* + *first preference dataset*, not a competitive model.

Plan to do **3-5 cycles** before assessing model quality against the existing MLX-lane baseline. Don't celebrate after round-1.

## Architecture

No new abstractions, no schema changes. Three independent PRs, each scoped to a single file or pair. All under the 400-line cap. The pipeline_v2 validator from PR 3 of the Modal-migration spec is the filter at generation time (no changes here).

## Tech stack

Existing — Modal Python lane (Mistral-Nemo-Base-2407, TRL/PEFT/bnb), pipeline_v2 filters, Postgres survey DB via `psycopg2`. No new dependencies.

---

## Wave A — three PRs in parallel

All three touch disjoint files. Dispatch in a single assistant turn with `isolation: "worktree"` and `run_in_background: true`. Each is independently mergeable; usage-order is generation → rating → re-training but code dependencies are zero.

| PR  | Title                                                                       | Files                                                              | Approx LOC |
|-----|-----------------------------------------------------------------------------|--------------------------------------------------------------------|------------|
| α   | `04_generate.py` Modal palier — round-N candidate generator + pipeline_v2   | `modal_jobs/04_generate.py`                                        | ~270       |
| β   | `extract_winners.py` — survey DB → winners JSONL + `data/external/` guard   | `scripts/clue_generation/extract_winners.py`, `.gitignore`         | ~110       |
| γ   | `05_raft_finetune.py` — manifest extension + runbook for round-0/round-1   | `modal_jobs/05_raft_finetune.py`, `docs/runbooks/clue-loop.md`     | ~300       |

---

## PR α — `04_generate.py` Modal palier

**Files**
- Create: `modal_jobs/04_generate.py`

**Mandatory ADR pre-read (run `scripts/adr-context.sh modal_jobs/04_generate.py scripts/clue_generation/modal/`)**

ADR-0001, ADR-0013, ADR-0057. Style guide v2 (`docs/clue-ai/style-guide-v2.md`) is the prompt source of truth — read in full.

**Spec**

A Modal job that:
1. Loads the latest LoRA adapter from the Modal volume (path matches `03b_finetune.py`'s output: `/vol/checkpoints/<run-tag>/adapter`).
2. Loads a lemma list (default: `data/curated/round_n_lemmas.csv` — see PR γ for round-0/1 lemma curation).
3. Generates `N` candidates per `(lemma, style)` pair across the 5 active styles (definition_directe, periphrase, culturel, cryptique, fonction_role). Default `N=1`, configurable via Modal function param.
4. Runs each candidate through pipeline_v2 (the 8 filters + 2 fused MLX gates per ADR-0057).
5. Writes accepted candidates to `/vol/generations/round_<n>/candidates.jsonl` with full provenance: `mot, definition, pos, categorie, style, force_estimated, longueur, source='modal_round_<n>', source_batch=<run-id>, generated_at`.
6. Emits a summary JSON at `/vol/generations/round_<n>/summary.json` with counts: requested / generated / pipeline_v2_passed / dropped_by_filter[id].

**Modal app config**
- App name `bliss-clue-round-generate`. GPU: A100-40GB (matches `03b_finetune.py`). Timeout: 30 min. Secret: `huggingface-token`.
- Volume: same `bliss-clue-vol` as the other paliers; mount at `/vol`.

**Success criteria**
- `python -m modal run modal_jobs/04_generate.py::generate --run-tag gold-v1 --round 1 --lemmas data/curated/round_1_lemmas.csv --n-per-pair 1` produces `candidates.jsonl` with ≥1 row.
- pipeline_v2 reuse is verified (no copy-paste of filters — import from `scripts/clue_generation/pipeline_v2/`).
- Summary JSON correctly counts dropped-by-filter.
- Unit test: a small fixture run with a mocked LoRA model that asserts the file structure.

**Risks**
- The existing `03b_finetune.py` adapter format is TRL/PEFT — load it the same way (`PeftModel.from_pretrained`). Don't reinvent.
- pipeline_v2 may not yet be installed on the Modal image — verify `image = (modal.Image...)` matches `03b_finetune.py`'s image. If pipeline_v2 isn't there, add it to the same image.
- Lemma list curation is out of scope for this PR; PR γ's runbook handles round-0/1 lemma picks.

**Comments style**: one-line non-obvious why only. Read CLAUDE.md.

---

## PR β — `extract_winners.py` + `.gitignore` guard

**Files**
- Create: `scripts/clue_generation/extract_winners.py`
- Modify: `.gitignore` (add `data/external/` defensive rule)

**Mandatory ADR pre-read (run `scripts/adr-context.sh scripts/clue_generation/ .gitignore`)**

ADR-0001, ADR-0013, ADR-0023, ADR-0056, ADR-0057.

**Spec**

A standalone CLI that:
1. Connects to the survey-api Postgres database (via `psycopg2`, connection from env `SURVEY_DB_URL` or `~/.bliss/survey-db-url` file — runbook will document).
2. Queries:
   ```sql
   SELECT si.mot, si.definition, si.pos, si.categorie, si.style,
          si.force_claimed, si.longueur, si.source, si.source_batch, si.expected,
          r.qualite, r.user_id, r.created_at
     FROM survey_items si
     JOIN ratings r ON r.item_id = si.item_id
    WHERE r.user_id = $1::uuid
      AND r.qualite = 5
      AND r.flag IS NULL
      AND si.retired_at IS NULL
      AND si.source LIKE 'modal_round_%'
   ORDER BY r.created_at;
   ```
3. Writes accepted ratings to `data/lora/modal_corpus_v1/winners_round_<n>.jsonl` in the same schema as the existing gold manifest's JSONL (compatible with `prepare_dataset.py` from the modal-migration spec PR 4).
4. Prints a summary: total ratings considered, kept, dropped (by reason: tier, source-pattern miss).

**CLI**

```sh
python -m scripts.clue_generation.extract_winners \
   --user-id <UUID> --round 1 --out data/lora/modal_corpus_v1/winners_round_1.jsonl
```

`--user-id` is required and explicit — never default to all users; round-0 training is single-rater per the maintainer decision recorded in the orchestration log.

**`.gitignore` addition (3 lines, top-aligned with the existing `data/dbnary/` block)**

```
# Licensed external corpora (Lexique3 CC BY-SA, etc.): stay local,
# never redistribute. ADR-0013 §1, ADR-0023.
data/external/
```

**Success criteria**
- Local run against the prod DB (with read-only credentials) produces a non-empty JSONL after a few `/sondage` ratings exist.
- Unit test: a Postgres testcontainer with seeded `survey_items` + `ratings` rows verifies the SQL filter + output shape.
- `.gitignore` rule verified by `touch data/external/lexique383.tsv && git status` showing the file ignored.

**Risks**
- The `qualite=5` filter encodes the round-0 verdict mapping (`GOOD → qualite=5`). If the simplification plan diverges (e.g., adds a `verdict` enum column), this script needs updating — flag in the runbook.
- Live DB read from a script the agent runs locally: use **read-only** credentials only. The runbook documents the credential path.

**Comments style**: one-line non-obvious why only.

---

## PR γ — `05_raft_finetune.py` + runbook

**Files**
- Create: `modal_jobs/05_raft_finetune.py`
- Create: `docs/runbooks/clue-loop.md`
- Modify: `data/lora/modal_corpus_v1/manifest.toml` (add `winners_round_<n>` slot, weight=1 default)

**Mandatory ADR pre-read (run `scripts/adr-context.sh modal_jobs/05_raft_finetune.py data/lora/modal_corpus_v1/`)**

ADR-0001, ADR-0013, ADR-0057. The clue-ai-modal-migration spec §3.7 (corpus fusion strategy) — winners_round_<n> slots into the same manifest-driven builder.

**Spec — `05_raft_finetune.py`**

Light wrapper around `03b_finetune.py` that:
1. Accepts `--round` and `--manifest-snapshot` args.
2. Invokes the manifest-driven corpus builder (from migration-spec PR 4) with `winners_round_*.jsonl` slots included.
3. Hands off to `03b_finetune.py::finetune` with the round-tagged run-id (e.g., `raft-round-1`).

If `03b_finetune.py` can be parameterized via the manifest path alone, this wrapper is ≤80 LOC — mostly arg-parsing + run-id stamping. **Resist the urge to fork the trainer.** Reuse `03b_finetune.py` directly; this PR adds a thin entrypoint, not a parallel implementation.

**Spec — `docs/runbooks/clue-loop.md`**

End-to-end runbook covering:
1. **Round-0 prep:** which lemmas, manifest snapshot, `03b_finetune.py` invocation, expected duration, where the adapter lands.
2. **Round-0 → Round-1 transition:** `04_generate.py` invocation (N candidates per lemma), where candidates land, how to import into `survey_items` (a one-off `import_candidates.py` CLI is out of scope here — runbook documents using the existing migration-spec PR 7 `export_adapter_to_csv.py` pattern in reverse).
3. **Round-1 rating session:** open `/sondage`, rate to target count (~100 ratings), monitor k-coverage.
4. **Round-1 RAFT step:** `extract_winners.py` invocation, snapshot the manifest, `05_raft_finetune.py` invocation, expected duration, where the round-1 adapter lands.
5. **Round-2 lemma curation:** how to pick the next 100 lemmas (defer detailed strategy; first pass = same lemmas as round-1, second pass = expand).

**Round-0 lemma list curation** — the runbook documents that for round-0, we use `gold_pilot_v1.csv`'s 100 unique words as the lemma set; for round-1 we pick 100 new words from a candidate pool (e.g., `data/curated/lemma_pool.csv` if it exists, else from Grammalecte-lemma-frequency per ADR-0014). The runbook stays light here — round-2+ lemma curation is its own deferred workstream.

**Manifest extension**

```toml
# data/lora/modal_corpus_v1/manifest.toml — append a slot:
[[sources]]
name = "winners_round_1"
path = "winners_round_1.jsonl"
weight = 1
optional = true   # file may not exist before round 1
```

**Success criteria**
- `python -m modal run modal_jobs/05_raft_finetune.py::finetune --round 1` consumes `winners_round_1.jsonl` (if present), runs the trainer to completion, deposits adapter at `/vol/checkpoints/raft-round-1/adapter`.
- Runbook walks a fresh agent through round-0 → round-1 end-to-end in one read-through.
- Manifest extension is backward-compatible: `optional = true` means absent files don't break the builder.

**Risks**
- The wrapper is tempting to over-design. Keep it small. If you find yourself reimplementing trainer config, stop and just `import` from `03b_finetune.py`.
- The runbook is for SOLO use (maintainer + one orchestrator). It does NOT need to cover multi-rater calibration, A/B comparison frameworks, or eval automation — those are deferred.

**Comments style**: one-line non-obvious why only.

---

## Cap-override pre-flag

This wave's largest PR (γ) is ~300 LOC across two files. Under the 400-line cap. No cap-override expected. If γ exceeds at write-time, split runbook into its own follow-up PR rather than invoking the override.

## Dispatch checklist

- [ ] Agents launched in parallel with `isolation: "worktree"` and `run_in_background: true`.
- [ ] Each agent prompt inlines its PR's full spec (the file paths above are not enough — paste the spec body).
- [ ] Each agent prompt instructs to run `scripts/adr-context.sh` BEFORE editing.
- [ ] Each agent prompt sets `frontend` or `jvm-backend` skill — actually this is Python/Modal work; no domain skill applies. Skip skill invocation.
- [ ] Each agent prompt budgets 3 auto-fix passes max.

## Comment-style pre-flag (apply to every agent prompt)

**Comments document non-obvious WHY, in one line.** Default to no comment. Single-line, non-obvious why only — a hidden constraint, a subtle invariant, a workaround for a specific bug. **No multi-line comment blocks. No multi-paragraph docstrings.** Don't reference PRs / tasks / callers / the current fix.

## Sequence (post-merge)

1. PRs α + β + γ merge in parallel as CI clears.
2. Maintainer runs the runbook (round-0 SFT on gold pilot v1 → adapter on Modal volume).
3. Maintainer runs `04_generate.py` for round-1 (100 candidates).
4. Round-1 candidates imported into `survey_items` (one-off — runbook documents).
5. Maintainer rates via `/sondage` (~100 ratings; UI is the in-flight simplification's GOOD/BAD/SKIP).
6. Maintainer runs `extract_winners.py` → `05_raft_finetune.py` for round-1.
7. Round-2 starts from the round-1 adapter.

## Out of scope

- A new `/sondage` rating UI (GOOD/BAD/SKIP) — that's the `sondage-simplification` plan, parallel workstream.
- Eval automation / regression tests against the MLX baseline.
- KTO trainer.
- DPO trainer (deferred to ≥500 accumulated preferences).
- Multi-rater calibration.
- Round-2+ lemma curation strategy (deferred to a follow-up after observing round-1 results).
- `import_candidates.py` to ingest `04_generate.py` output into `survey_items` (runbook documents a manual SQL `INSERT` pattern for round-0; this is a small follow-up if the manual path proves painful).
