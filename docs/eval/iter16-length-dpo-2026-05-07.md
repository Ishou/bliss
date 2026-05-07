# iter16 — length-preference DPO corpus (2026-05-07)

The infrastructure for the relaxed `MAX_CLUE_CHARS = 25` cap shipped in
PR #208 (drop-compact + cap-relax). v7's outputs are all ≤18 chars by
training, so the cap relaxation is forward-facing infrastructure — it
buys nothing in shipped quality until a new iter actually exercises the
18-25 char range. iter16 authors that corpus.

## Goal

Teach the LoRA to prefer shorter clues when an equally-good shorter
equivalent exists, without trading sense accuracy for brevity.

## Corpus shape

171 hand-authored preference pairs across three flavours:

| flavour | n | what the contrast teaches |
|---|---|---|
| A — length-only | 61 | shorter equivalent preferred, both ≤25 chars |
| B — cap-violation | 50 | rejected >25 chars; equivalent shorter <25 chars wins |
| C — preamble-strip | 60 | bare clue beats formulaic scaffolding (Action de / Pratique de / Personne qui / État de / Manière de) |

Splits (stratified per flavour, 80/10/10):

| split | A | B | C | total |
|---|---|---|---|---|
| train | 49 | 50 | 40 | 139 |
| valid | 6 | 6 | 4 | 16 |
| test | 6 | 6 | 4 | 16 |

Combined with iter13_combined (308/36/36 = 380 PP-frame and stem/sense
fixes from iter12+iter13) to avoid catastrophic forgetting:

| split | total combined |
|---|---|
| train | 447 |
| valid | 52 |
| test | 52 |

## Authoring discipline

Each pair was hand-authored under the constraint that **both** sides pass
`validate_lemma_clue` with `flag = "ok"` (in category B, `rejected` is
allowed to fail `too-long` by construction; everything else must validate).
The build script at `data/lora_dpo_iter16_length/build_corpus.py`
enforces this and drops anything that doesn't validate. Result: 171/171
candidates accepted.

The validator constraint is non-trivial because it shapes which
verbose-pattern rejected entries are *plausible LoRA output*:

- "Qui est X" patterns (rejected = "Qui est sans voix" for ADJ targets)
  fail `pos-mismatch` — the head is `est` (verbe), not the target ADJ.
  Validator drops these, so the LoRA never emits them. DPO can't teach
  against patterns the LoRA can't produce. Excluded.
- Adj-targeted "verbose" rejected entries are unusable in this corpus
  for the same reason — every realistic adj-headed clue *already* has
  an adj head. Adj contrasts in flavour A use the form
  `chosen=<adj>` vs `rejected=<adj> + et + <adj-syn>` (head still adj,
  same lemma, just verbose).
- Verb-targeted "Action de [V]" patterns also fail `pos-mismatch`
  (head=Action is nom, target POS=verbe). For verb targets, contrasts
  use `<verb-inf>` vs `<verb-inf> + adverb` shapes.
- Noun-targeted preamble-strip patterns are the only place where
  formulaic preambles validate — head matches target POS=nom because
  the preamble noun ("Action", "Personne", "État", "Manière") is the
  head. Flavour C is therefore noun-only.

This is a tighter teaching surface than I'd initially scoped, but it's
the surface the LoRA actually inhabits. Patterns the validator already
rejects don't need DPO; the validator handles them.

## Training config

`scripts/clue_generation/lora_iter16_length_dpo.yaml`:

- Base: `command-r-08-2024-4bit`
- Resume from: `models/lora-clue-v3/adapters.safetensors` (SFT, same as
  iter13 — DPO refines SFT, not stacked on prior DPO)
- Output: `models/lora-clue-v9` (v8a/b/c were iter15 experiments)
- 400 iters at lr 1e-6, β 0.1, sigmoid loss, rank 32, num_layers 16
- Promote best-val-loss adapter manually (do NOT take the last
  checkpoint — train loss keeps falling on small corpora)

Iter count bumped from iter13's 320 because the corpus is ~45% larger.

## Eval plan

Two measurements before deciding to ship:

1. **Length-pref acceptance** on the held-out test split (52 pairs).
   Score is the fraction of pairs where the model assigns higher logprob
   to `chosen` than `rejected`. iter13 hit ~85% on a comparable holdout;
   iter16 should be in the same range, with the failure cases
   concentrated in flavour A (subtle contrasts).

2. **Semantic acceptance regression check**: standard 200-lemma N=3
   best-of-3 pipeline at the production filter threshold (T=0.65). If
   user-rated acceptance drops more than 3pp vs v7 baseline, the model
   is trading sense for brevity. The 5-sample variance check is the
   gate against false alarms.

## What this won't move

- iter16 doesn't address the **dobj-partitive** gap in `pp-only-skipped`
  (the `compliqué → "Ajouté de la difficulté"` failure mode from PR
  #203's session log). That's a separate validator-side fix on
  `_has_verb_dobj_frame` to catch `de la` / `de l'` after head.
- iter16 doesn't fix **multi-head clues** (`V1 + et + V2`), the new
  failure mode I surfaced in PR #203's diagnostic. That needs either
  an inflater extension (walk all heads) or a DPO theme of its own.
- iter16 won't materially improve **sense accuracy** on polysemic
  lemmas. Length preference is orthogonal to sense disambiguation.

## What's NOT included

- 3-way (Y / B / N) preference encoding via multiple pairwise pairs
  per lemma. The user expressed interest in that in the session, but
  it triples the authoring cost and the gain over plain (Y, N) +
  (Y, B-correct-but-long) is unproven. Defer until plain pairwise
  iter16 lands and we can measure whether it's still under the
  3pp-uplift floor that warrants more sophistication.

## Files

- `data/lora_dpo_iter16_length/build_corpus.py` — the 171-pair source
  (validates each pair, splits 80/10/10, emits jsonl).
- `data/lora_dpo_iter16_length/{train,valid,test}.jsonl` — the iter16-only
  corpus.
- `data/lora_dpo_iter16_length_combined/{train,valid,test}.jsonl` —
  iter16 + iter13_combined, the corpus actually trained against.
- `scripts/clue_generation/lora_iter16_length_dpo.yaml` — training
  config, ready to launch.
