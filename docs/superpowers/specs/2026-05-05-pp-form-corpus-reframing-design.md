# PP-form clue mismatch — corpus-side reframing (iter13) + retire `dbnary-synonym`

Date: 2026-05-05
Status: Draft (auto-mode authored, awaiting user review)
Author: Claude (autonomous session)
Anchor issue: PR #194 v2 (in flight) — past-participle surface placeholders.

## 1. Problem (one sentence)

Surface forms that are past participles used adjectivally (`forée`, `accordée`, `aggravée`) cannot be served by the LoRA's verb-lemma clue when that clue uses a `verb + direct-object` frame, because the frame is not PP-inflectable in French.

The bug is a **syntactic-frame mismatch**, not a semantic-category mismatch:

| Lemma clue                  | Frame              | PP-inflate result          | Grammatical | State-clue |
|-----------------------------|--------------------|----------------------------|-------------|------------|
| `Percer un trou`            | `verb + DObj`      | `Percée un trou`           | ❌          | n/a        |
| `Munir d'un trou`           | `verb + de + N`    | `Munie d'un trou`          | ✅          | ✅         |
| `Trouer` (single-word verb) | `verb`             | `Trouée`                   | ✅          | ✅         |

A `de`-complement (`munir de`, `doter de`, `pourvoir de`, `équiper de`) carries
through the participle as an instrumental/possessive state. A direct-object
frame doesn't — the PP loses the DObj slot and you get word salad.

## 2. Goal

Make the LoRA emit, for verbs whose surface forms include high-frequency
past-participle adjectival rows, a clue that is **PP-inflectable** —
either a `verb + de/à + N` frame or a single-word verb synonym lemma.

Acceptance signal (user-set, see §6):

> Hand-rate 100 PP-only surfaces; ≥ 80% acceptance.

Plus: actively migrate off the `dbnary-synonym` source (per ADR-0024) so the
`clue_text` write-path becomes pure-LoRA. DBnary text never enters
`clue_text`; DBnary feeds the filter at training time only. ADR-0024 will need
a follow-up ADR to record this deprecation; that ADR is **out of scope** for
this design but will be referenced from the iter13 row in
`docs/eval/clue-gen-v0.md`.

## 3. Non-goals

- No new ADR-class change to ADR-0023. Constraint #1 (`definition_text` never
  leaves offline) stays load-bearing. No DBnary text in the generator's
  prompt, in the SFT corpus, in DPO pairs, or in the runtime CSV.
- No deployed inference. The pipeline stays local-dev only per ADR-0013 §8.
- No widening of `_find_pleonasm`, `validate_clue` flags, or the inflater's
  syncretism handling. Those are stable post PR #192 / #193.
- No surface-form generation. Lemma-form generation + head-token inflation
  remains the contract.

## 4. Architecture

Two complementary changes. Both are required; neither is sufficient alone.

### 4.1 Generator change (corpus-side reframing — DPO)

A new DPO refinement, **iter13**, on top of the SFT v3 adapter
(`models/lora-clue-v3`), training on a hand-authored preference corpus that
encodes the rule:

> *For verbs whose surface forms include adjectival past-participle uses,
> prefer a PP-inflectable frame (`verb + de/à + N` or a single-word verb
> synonym) over a `verb + DObj` frame.*

Mechanism specifics (per playbook §"LoRA training — corpus + config"):

- `train_type: lora`, `train_mode: dpo`.
- `resume_adapter_file: models/lora-clue-v3/adapters.safetensors`.
- `learning_rate: 1.0e-6`, `dpo_loss: sigmoid`, `dpo_beta: 0.1`.
- `num_layers: 16`, `batch_size: 2`.
- `iters` short, capped at 200; promote the **best-val-loss** adapter to
  `models/lora-clue-v6`.
- New corpus at `data/lora_dpo/pp_reframing_iter13.jsonl` (~200–400 pairs;
  the count in §5.3 below explains the target).

Why DPO not SFT: the change is preference-shaped (prefer this frame over
that), the existing iter12 already established DPO infrastructure, and DPO
mass-rebalances without re-teaching surface knowledge. If iter13 misses the
gate by >5pp, escalate to **iter14 = SFT injection + DPO** (hand-author
seeds, retrain SFT, then mine new DPO pairs). Iter14 is documented in §10
as the fallback path; not implemented in this design.

### 4.2 Inflater change (defensive PP+DObj skip)

Even after iter13, some verbs will still get a `verb + DObj` lemma clue
(distribution mass not eliminated by DPO). For those, `inflect_clue.py`
must detect the frame at inflation time and **emit a `pp-only-skipped`
flag**, not a broken `Percée un trou`.

Detection rule (closed-set determiner check): the head verb is followed by a
determiner (`un`, `une`, `des`, `le`, `la`, `les`, `l'`, `du`, `de la`,
demonstratives, possessives) followed by a content-word noun. If a `de` /
`à` preposition sits between the verb and the determiner (`Mettre en
ordre`, `Munir d'un trou`), the frame is PP-inflectable and the skip does
**not** fire. This is the same shape PR #194 v1 used for its 3sg/3pl
fallback; we keep the detector and **drop the fallback** because the
fallback was the v1 bug (action-clue masquerading as state-clue).

When the skip fires, `build_surface_clues.py` writes the surface row with
`inflection_status = pp-only-skipped` and the runtime `merge_clues_into_wordlist.py`
keeps the placeholder `clue == word` for that surface. The grid renderer
already treats `clue == word` as "no clue available."

This is what PR #194 v2 was going to ship; we land it here as part of the
same shipping unit since iter13's effect on the residual is the only
correct counterfactual to measure against.

### 4.3 Migration: retire `dbnary-synonym`

After iter13's full re-run lands a new `words-fr.csv`:

1. `findTopBySourcePriority` (in `JdbcClueCandidateRepository.kt`) drops
   `'dbnary-synonym'` from its priority list. Existing rows in the
   `clue_candidates` table stay (no destructive DB change), they just
   aren't picked.
2. `bliss-worker derive-synonym-clues` keeps working but is no longer
   wired into `run_production.sh`. We don't delete the subcommand —
   keeping it lets us A/B-revive it if iter13 measurably regresses
   noun coverage.
3. `bliss-worker export-words` re-exports `words-fr.csv` from the new
   priority order. Diff visible in PR-C (§8).

User-stated risk acceptance: "the file is versioned so it'd be easy to
recover."

## 5. Components

### 5.1 PP-productive verb detection — `scripts/eval/find_pp_productive_verbs.py`

Input: `grid/api/src/main/resources/words/words-fr.csv`, grammalecte lexique.

Algorithm:

1. For every row in `words-fr.csv` with length 4–11 and alphabetic surface:
   look up the surface in grammalecte; for each `(lemma, pos)` candidate,
   note whether the surface is tagged `ppas`.
2. Group by `lemma` where `pos == 'verbe'`. A verb is **PP-productive** if
   it has ≥ 1 surface row with `ppas` tag and grammalecte `Total
   occurrences` ≥ 1000 on at least one of those surfaces.
3. For each PP-productive verb, also collect its surfaces tagged
   `{ppas, mas, sg}`, `{ppas, fem, sg}`, `{ppas, mas, pl}`, `{ppas, fem, pl}`.

Output: `data/eval/pp_productive_verbs.csv` with columns:

```
lemma,n_pp_surfaces,top_pp_surface,top_pp_freq
forer,4,forée,128432
accorder,4,accordée,2.1M
aggraver,4,aggravée,89421
...
```

Expected scale: ~600–1500 verbs (the user's headline number — "loses ~410
rows of coverage from PR #194 v2" — is at *surfaces*; verbs are 1/4 of that
at most, since each PP-productive verb contributes up to 4 PP surfaces).

### 5.2 PP+DObj detection (refactor of inflect_clue) — `scripts/eval/inflect_clue.py`

Add `_has_verb_dobj_frame(tokens, head_idx)` — returns `True` when the
token immediately after the head is a determiner from the closed set, AND
the next content-word token is tagged `nom`. Returns `False` if a `de` /
`à` / `en` / `dans` preposition sits between the head and the determiner.

When `_has_verb_dobj_frame` is `True` AND target morphology is `ppas`:
return `InflectionResult(status="pp-only-skipped", inflected_clue=None)`
instead of inflecting.

`build_surface_clues.py` reads this status and writes
`inflection_status = pp-only-skipped` to `surface_clues.csv`;
`merge_clues_into_wordlist.py` filters such rows (placeholder shipped as
`clue == word`).

### 5.3 DPO preference corpus — `data/lora_dpo/pp_reframing_iter13.jsonl`

Schema (mlx-lm DPO format):

```json
{"prompt": "<lemma>", "chosen": "<PP-inflectable clue>", "rejected": "<verb+DObj clue>"}
```

Target: **240 pairs** (3 frame variants × 80 PP-productive verbs).
Variants per verb:

- `chosen` v1 = single-word verb synonym lemma where one exists (e.g.
  `forer → Trouer`; PP-inflates to `Trouée`).
- `chosen` v2 = `verb + de + N` reframing of the rejected DObj clue
  (e.g. `forer → Munir d'un trou`).
- `chosen` v3 = single-word verb synonym variant (different
  synonym; e.g. `forer → Percer` is **not** valid because PP would shadow
  the lemma — apply `self-leak` validator before adding to corpus).

Quality gates on each pair before commit:

- Both `chosen` and `rejected` pass `validate_lemma_clue` (no `unknown-head`,
  no `head-not-lemma`, no `pos-mismatch`, no `pleonasm`, no `self-leak`,
  no `stem-leak`).
- `chosen`'s head, when PP-inflated to `{ppas, fem, sg}` against the verb's
  paradigm, produces a grammatical token via `MorphologyIndex.inflect`.
- `rejected` triggers `_has_verb_dobj_frame`.

Checked into git under `data/lora_dpo/` (per playbook §"Data layout — rated
CSVs that back a docs/eval iter row must be checked in").

### 5.4 Iter13 yaml — `scripts/clue_generation/lora_iter13_dpo.yaml`

Copy of `lora_iter12_dpo.yaml` with the diff captured in a header comment.
Key changes:

- `data: data/lora_dpo/pp_reframing_iter13.jsonl`
- `adapter_path: models/lora-clue-v6`
- `iters: 200` (same cap as iter12; promote best-val-loss).

### 5.5 Worker priority change — `JdbcClueCandidateRepository.kt`

In the `findTopBySourcePriority` SQL ordering, drop the `'dbnary-synonym'`
entry from the priority CASE. Add a JavaDoc note: "ADR-0024-followup —
deprecated by iter13, see docs/eval/clue-gen-v0.md row iter13."

## 6. Eval

Methodology mirrors the playbook §"Eval methodology":

1. Sample 100 PP-only surfaces from the post-iter13
   `data/eval/production/surface_clues.csv` (rows with
   `inflection_status ∈ {inflected, identity}` AND surface morphology
   contains `ppas` AND surface ≠ lemma). If fewer than 100 PP-only
   surfaces survive, use what's available and flag in the logbook row.
2. **Self-rate** y/b/n. Calibration is "self," not "user" — record this
   in the logbook row (10pp gap per playbook §"Eval methodology").
3. Compute acceptance = (y + 0.5·b) / N.
4. **5-sample variance check** (`scripts/eval/run_top_x.sh` analogue with
   seeds 20260601-05) — only required if the headline number lands within
   3pp of the gate. Otherwise the gate clearance is unambiguous.
5. **Gate**: ≥80% self-rated acceptance on the 100-surface sample. With
   the 10pp self-vs-user gap, this maps to ≈70% user-rated, which still
   clears the playbook's 70–85% "fine-tune" tier.

If the gate fails by ≤5pp: escalate to iter14 (SFT injection — see §10).
If by >5pp: stop, write a logbook row, hand-back to user — the failure is
mechanistic, not gradient-tunable.

## 7. Data flow

```
words-fr.csv ─► find_pp_productive_verbs.py ─► pp_productive_verbs.csv
                                                       │
                                                       ▼
                                  (hand-author 240 DPO pairs)
                                                       │
                                                       ▼
                                pp_reframing_iter13.jsonl
                                                       │
                            ┌──────────────────────────┘
                            ▼
              mlx_lm.lora --config lora_iter13_dpo.yaml
                            │
                            ▼
                   models/lora-clue-v6 (DPO adapter)
                            │
                            ▼
              run_production.sh GEN_ADAPTER=lora-clue-v6
                            │
                            ▼
                   lemma_clues_shipped.csv (new)
                            │
                            ▼
              build_surface_clues.py (with PP+DObj skip)
                            │
                            ▼
                   surface_clues.csv (new)
                            │
                            ▼
              merge_clues_into_wordlist.py
                            │
                            ▼
              bliss-worker ingest-clue-candidates --source command-r-lora-v6-iter13
              bliss-worker export-words (priority CASE without dbnary-synonym)
                            │
                            ▼
                   words-fr.csv (committed, regen)
```

## 8. Rollout — three PRs

CLAUDE.md mandates <400-line PRs and forbids unrelated bundling. This work
splits naturally:

- **PR-A — Tooling + iter13 corpus.** Files: `scripts/eval/find_pp_productive_verbs.py`,
  `scripts/eval/inflect_clue.py` (PP+DObj skip), `scripts/clue_generation/lora_iter13_dpo.yaml`,
  `data/lora_dpo/pp_reframing_iter13.jsonl`, `data/eval/pp_productive_verbs.csv`,
  unit tests. No model weights, no `words-fr.csv` diff. Reviewable.
- **PR-B — Iter13 adapter + production re-run + words-fr.csv regen.** Files:
  `models/lora-clue-v6/` (gitignored at scale; the eval CSVs that back the
  iter13 logbook row are checked in: `data/eval/lemma_clues_iter13.csv`,
  the rated 100-PP surface CSV under `data/eval/`),
  `grid/api/src/main/resources/words/words-fr.csv` (regenerated),
  `docs/eval/clue-gen-v0.md` (iter13 row appended).
- **PR-C — Retire `dbnary-synonym`.** Files:
  `grid/infrastructure/.../JdbcClueCandidateRepository.kt` (priority CASE),
  `grid/api/src/main/resources/words/words-fr.csv` (re-export),
  `docs/adr/0024-dbnary-synonym-lemma-as-direct-clue-candidate.md`
  (status note + pointer to iter13).

Conditional: PR-B and PR-C are gated on PR-A landing AND iter13 clearing
the §6 gate. If iter13 fails by >5pp, only PR-A ships (the defensive skip
alone is still a real improvement vs the broken `Percée un trou` on
main).

## 9. Risks

| Risk | Mitigation |
|---|---|
| iter13 DPO over-rotates and starts emitting `verb + de + N` framings even where DObj is correct (e.g. for verbs whose PP isn't adjectivally productive) | DPO at lr 1e-6, β=0.1 is small-step by design. Run a regression eval on the iter12 100-lemma sample (non-PP) and confirm acceptance hasn't dropped >2pp. |
| Hand-authored DPO pairs leak into eval set | Eval samples PP-only surfaces from the **production** run, not from the corpus. Corpus-vs-eval lemmas non-overlapping by construction (iter13 corpus is verb-lemmas; eval set is PP surfaces, distinct word_ids). |
| Retiring `dbnary-synonym` regresses noun coverage where iter13 didn't pick up the slack | Acknowledged user-side. The CSV is versioned; revert is `git revert` of PR-C. PR-B's words-fr.csv diff (with dbnary-synonym still in priority) is the safe-fallback if PR-C needs to be unwound. |
| The 80-verb DPO sample isn't enough mass to shift the distribution | iter12 DPO had ~150 pairs and shifted the eval needle by 4pp. 240 pairs at 3:1 chosen-rejected ratio is a reasonable scale-up. |
| `pp-only-skipped` placeholder count rises noticeably (the bridge fix made ~410 surfaces placeholder; we want that count to drop, not stay flat) | Reported in the iter13 logbook row as `pp_only_skipped_before_iter13` vs `pp_only_skipped_after_iter13`. If the after-count is within 10% of the before-count, iter13 didn't shift the corpus and we should escalate to iter14 even if the 100-surface gate clears. |

## 10. Fallback — iter14 SFT injection

If iter13 misses the gate by ≤5pp: hand-author 100 `(PP-productive verb,
PP-friendly clue)` SFT seeds (citation-form clue, not preference pair),
append to `data/lora/train.jsonl`, retrain SFT as iter14, then DPO on the
existing iter13 preference corpus on top. Promote `models/lora-clue-v7`.
Re-run §6 eval. Fallback only — not implemented in this design unless gate
fails.

## 11. Out-of-scope follow-ups

- ADR for `dbnary-synonym` deprecation. Open as a follow-up commit on PR-C.
- ADR for "DBnary text never in `clue_text`" if the user wants a stricter
  re-statement of ADR-0023 constraint #1 (currently implicit via
  ADR-0024's narrow relaxation; an ADR-0025 could explicitly close the
  relaxation).
- Multi-token agreement in inflater (adjective tracking noun gender across
  the clue). Already out of scope per `inflect_clue.py` header.
- Retraining the filter on iter13 outputs. The playbook says retrain
  filter "only when the failure-mode mix changed materially" — we'll
  measure that in §6's logbook row and decide.

## 12. Spec self-review notes

Placeholder scan: no TBD/TODO. Internal consistency: §5.5 and §8 PR-C
both reference dropping `'dbnary-synonym'` from priority — consistent.
§4.1 says "drop fallback (v1 bug)" and §4.2 says "keep detector, drop
fallback" — consistent. Scope: focused on iter13 + retirement; iter14 is
clearly fallback-only. Ambiguity: §6 step 4 ("only required if within
3pp") is the only fuzzy edge — fine, the guidance is clear ("else gate
clearance unambiguous").
