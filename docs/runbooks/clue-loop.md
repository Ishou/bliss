# Clue training loop runbook — Modal lane

End-to-end procedure for the iterative clue-generation training loop
on Modal: round-0 SFT on the gold pilot v1, then RAFT (rejection-
sampling SFT) cycles seeded by `/sondage` ratings.

Algorithm choice is RAFT, not DPO. Rationale + expectations live in
`docs/superpowers/plans/2026-05-26-clue-loop-raft.md`. Backbone design
is ADR-0057 (Modal cloud-GPU lane). This runbook is for **solo
operation** — one maintainer, one rater, no calibration step.

## What's where — three independent counters

The clue-AI stack carries three numbering schemes that are easy to
confuse. They are not interchangeable.

| Counter         | Lane                              | What it tracks                          | What bumps it                                         |
|-----------------|-----------------------------------|-----------------------------------------|-------------------------------------------------------|
| `iter9`, `iter18`, … | **MLX** (Command-R, Apple Silicon) | LoRA adapter iteration in `docs/eval/clue-gen-v0.md` | Any new adapter trained on the MLX lane               |
| `modal_corpus_v1`   | **Modal**                        | Recipe version of the SFT corpus        | Structural manifest edit (new source, prompt change)  |
| `round-N`           | **Modal RAFT**                   | Training *cycle* within a corpus version | Each retrain after a fresh winners CSV is appended    |

The Modal lane has **two base-model forks** sharing the same RAFT
cycle counter:

| Fork              | Base model                  | Trainer script                       | source_batch prefix in `survey_items`         |
|-------------------|-----------------------------|--------------------------------------|-----------------------------------------------|
| Mistral-Nemo path | Mistral-Nemo-Base-2407 (12B) | `modal_jobs/03b_finetune.py`         | `mistral-nemo-pilot-v1-r<N>-<hash>`           |
| Command-R fork    | command-r-08-2024-4bit (35B) | `modal_jobs/03b_finetune_command_r.py` (lives on `experiment/command-r-base`) | `c4ai-command-r-pilot-v1-r<N>-<hash>` |

The Command-R fork has been the active iteration path since
2026-05-27 (commit `6894271a`). Round numbering continues across the
fork — at the time of writing, generations through r9 exist on the
Command-R fork; the Mistral-Nemo path has r1 only.

## How correctifs flow into training

Maintainer correctifs (rater proposes a replacement clue in `/sondage`)
create a new `survey_items` row with `source = 'rater_proposed'` and,
post-2026-05-30, `training_weight = 3.0` (gold). The auto-GOOD wiring
(commit `f21da63a`) also creates a `qualite = 5, flag NULL` rating on
the proposed item attributed to the maintainer.

**Pre-PR-#713 (rounds 1–9):** correctifs landed in the DB and were
silently dropped at extraction — `extract_winners.py` filtered by
`source_batch LIKE '%-r<N>-%'` which excluded the `rater_2026-*`
source_batches. Nothing trained on them.

**Round-10 onward (this runbook):** `extract_winners.py` widens the
WHERE clause to `(source_batch LIKE '%-r<N>-%' OR source = 'rater_proposed')`
and writes a CSV with the `training_weight` column. The manifest's
`winners_round_<N>` slot consumes it via `weight_column = "training_weight"`,
which replicates gold rows 3× during corpus build (PR #712 plumbing).

## Prereqs

- Modal CLI authenticated (`modal token new`) and HF secret created
  (`modal secret create huggingface HF_TOKEN=hf_…`) per
  `modal_jobs/README.md`.
- Paliers 0/1/2 of the Modal lane have already validated on this
  workstation (Mistral-Nemo-Base-2407 on volume `mots-fleches-models`).
- `survey-api` reachable at the configured URL (round-1+ only).
- Read-only Postgres credentials in `~/.bliss/survey-db-url` or
  `SURVEY_DB_URL` env. The `extract_winners.py` CLI reads from there.

## Sections

1. [Round-0 prep + SFT](#round-0-prep--sft)
2. [Round-0 to round-1 transition](#round-0-to-round-1-transition)
3. [Round-1 rating session](#round-1-rating-session)
4. [Round-N RAFT step](#round-n-raft-step)
5. [Round-2 lemma curation](#round-2-lemma-curation)

---

## Round-0 prep + SFT

Lemma set: the 100 unique words in `data/curated/gold_pilot_v1.csv`.
Round-0 does not need a separate lemma CSV — it is pure SFT on the
gold rows, no generation step.

1. Build the fused corpus from the live manifest:

   ```sh
   python3 -m scripts.clue_generation.modal.build_modal_corpus
   ```

   Output: `data/lora/modal_corpus_v1/{train,val}.jsonl` (≈1000 rows
   after weight replication) plus `build_summary.md`.

2. Upload to Modal volume `mots-fleches-datasets`:

   ```sh
   modal run modal_jobs/03a_upload_dataset.py
   ```

3. Fine-tune on Modal (≈25–35 min on A100-40GB, cost ≈ $1.50):

   ```sh
   modal run modal_jobs/03b_finetune.py
   ```

   Output adapter lands at `/adapters/mistral-nemo-pilot-v1` on volume
   `mots-fleches-adapters`. Tag it as the round-0 baseline:

   ```sh
   modal volume cp mots-fleches-adapters/mistral-nemo-pilot-v1 \
       mots-fleches-adapters/raft-round-0
   ```

Note: `03b_finetune.py` is not yet parameterized by run-id, so each
round currently overwrites `mistral-nemo-pilot-v1` on the volume; the
copy step above is what preserves the round-0 adapter before round-1
re-trains. A follow-up PR is expected to parameterize the trainer so
this manual tagging goes away.

---

## Round-0 to round-1 transition

Round-1 candidate set comes from running the round-0 adapter against
the same 100 lemmas across 5 styles. With `N=1` candidate per
`(lemma, style)` pair that is up to 500 raw candidates; pipeline_v2
typically drops 30–70 %, so expect 150–350 to land in `survey_items`.

1. Produce a lemma CSV for the round (same 100 words as round-0):

   ```sh
   awk -F';' 'NR>1 {print $1}' data/curated/gold_pilot_v1.csv \
       | sort -u > data/curated/round_1_lemmas.csv
   ```

2. Generate round-1 candidates on Modal:

   ```sh
   modal run modal_jobs/04_generate.py::generate \
       --run-tag raft-round-0 \
       --round 1 \
       --lemmas data/curated/round_1_lemmas.csv \
       --n-per-pair 1
   ```

   Output: `/vol/generations/round_1/candidates.jsonl` plus a
   `summary.json` with per-filter drop counts.

3. Pull the candidates to local disk:

   ```sh
   modal volume get mots-fleches-generations \
       round_1/candidates.jsonl \
       /tmp/round_1_candidates.jsonl
   ```

### Importing candidates into `survey_items`

There is no automation script for this step yet — it is a one-off SQL
pattern per round. The shape is documented here; copy-paste into a
`psql` session against the `survey-api` Postgres database.

Step 1 — load the JSONL into a staging table:

```sql
CREATE TEMP TABLE staging_round_1 (
    mot text,
    definition text,
    pos text,
    categorie text,
    style text,
    force_estimated smallint,
    longueur smallint,
    source text,
    source_batch text,
    generated_at timestamptz
);

\copy staging_round_1 FROM PROGRAM 'jq -r ''[.mot,.definition,.pos,.categorie,.style,.force_estimated,.longueur,.source,.source_batch,.generated_at] | @csv'' /tmp/round_1_candidates.jsonl' CSV;
```

Step 2 — insert into `survey_items` with the round tag in `source` +
`expected`:

```sql
INSERT INTO survey_items (
    item_id, mot, definition, pos, categorie, style,
    force_claimed, longueur, source, source_batch, tier, expected
)
SELECT
    gen_random_uuid(),
    mot,
    definition,
    pos,
    categorie,
    style,
    COALESCE(force_estimated, 3) AS force_claimed,
    longueur,
    'modal_round_1' AS source,
    source_batch,
    'mid' AS tier,
    jsonb_build_object('round', 1, 'generated_at', generated_at) AS expected
FROM staging_round_1;
```

The `source` prefix `modal_round_<n>` is what `extract_winners.py`
filters on in the WHERE clause — keep it stable across rounds.

---

## Round-1 rating session

1. Open `/sondage` in a browser, signed in as the maintainer account.
2. Rate to target count ≈ 100 verdicts (GOOD / BAD / SKIP, per the
   in-flight simplification UI). The stratified sampler picks items
   to maximise k-coverage; if the same items start cycling, the
   sampler has converged on the available pool and the next move is
   either accept (rate fewer than 100) or generate more candidates
   (raise `N` in step 2 above, or pick more lemmas).
3. Optional spot-check of k-coverage from the survey-api side:

   ```sql
   SELECT si.style,
          COUNT(DISTINCT r.item_id) AS rated_items,
          COUNT(DISTINCT si.item_id) AS total_items
     FROM survey_items si
     LEFT JOIN ratings r ON r.item_id = si.item_id
    WHERE si.source = 'modal_round_1'
      AND si.retired_at IS NULL
    GROUP BY si.style
    ORDER BY si.style;
   ```

The single-rater single-session model is deliberate for now; multi-
rater calibration is out of scope (see plan).

---

## Round-N RAFT step

1. Extract round-N winners to CSV. The extractor auto-resolves the
   maintainer from `maintainer_roles`; no UUID arg is needed. It pulls
   both r-N RAFT winners (`source_batch LIKE '%-r<N>-%'`) **and**
   maintainer correctifs (`source = 'rater_proposed'`) into the same
   file, with the `training_weight` column driving per-row replication
   downstream:

   ```sh
   python3 -m scripts.clue_generation.extract_winners \
       --round <N> \
       --out data/lora/modal_corpus_v1/winners_round_<N>.csv
   ```

2. Add a `winners_round_<N>` source to `manifest.toml` (replacing the
   prior round's slot, or alongside it — the build accumulates):

   ```toml
   [[sources]]
   name = "winners_round_<N>"
   path = "data/lora/modal_corpus_v1/winners_round_<N>.csv"
   tier = "gold"
   weight = 1
   csv_delimiter = ";"
   schema_mapping = { mot = "mot", definition = "definition", force = "force" }
   weight_column = "training_weight"
   row_filter = ""
   ```

3. Build the round-N corpus, snapshot the manifest, and emit the
   handoff commands:

   ```sh
   python3 modal_jobs/05_raft_finetune.py --round <N>
   ```

   This writes `data/lora/modal_corpus_v1/manifest.raft-round-<N>.toml`
   and refreshes `train.jsonl` / `val.jsonl` to include the new winners.

4. Upload + finetune. Use the trainer matching the active fork — for
   the Command-R fork (current production path), use
   `03b_finetune_command_r.py` from the `experiment/command-r-base`
   branch:

   ```sh
   modal run modal_jobs/03a_upload_dataset.py
   modal run modal_jobs/03b_finetune_command_r.py    # or 03b_finetune.py for the Mistral path
   modal volume cp mots-fleches-adapters/c4ai-command-r-pilot-v1 \
       mots-fleches-adapters/raft-round-<N>
   ```

Expected duration: ≈ 25–35 min on A100-40GB. Cost ≈ $1.50.

---

## Round-2 lemma curation

Round-2 reuses `data/curated/round_1_lemmas.csv` as the lemma set
unless the round-1 winners suggest a coverage gap. Round-3+ lemma
curation is its own deferred workstream — once round-1 results are
observed, the lemma pool will be expanded from
`data/curated/lemma_pool.csv` (or, if absent, from a Grammalecte-
lemma-frequency cut per ADR-0014).

Adding a new round to the manifest: see the round-N RAFT step above
for the CSV-backed source block. The builder is CSV-only; JSONL was
the original design but never wired into `build_modal_corpus.py`, so
the CSV path is the only working option today.

---

## Honest expectations

Round-0 → round-1 is **directional**, not absolute. The first cycle
exists to validate the loop infrastructure and produce the first
preference dataset, not to beat the MLX-lane baseline. Plan to run
3–5 cycles before assessing quality against `docs/eval/clue-gen-v0.md`.
