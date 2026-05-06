# PP-form clue mismatch — autonomous session notes (2026-05-05)

Working branch: `feat/clue-ai-iter13-pp-reframe-and-retire-synonym`.
Spec: `docs/superpowers/specs/2026-05-05-pp-form-corpus-reframing-design.md`.

## Headline reframings

- **The bug is a syntactic-frame mismatch, not a category mismatch.**
  `Percer un trou` (verb + DObj) doesn't PP-inflate to a grammatical
  state-clue. `Munir d'un trou` (verb + de + N) and `Trouer` (single-word
  verb synonym) do. The lemma corpus needs PP-inflectable framings for
  PP-productive verbs; that's the iter13 DPO target.
- **Per the user's architectural preference**, no DBnary text ever lands
  in `clue_text`. DBnary feeds the filter (training-time only). That
  rules out ADR-0024's synonym-direct lane as a permanent design and
  motivates option C (active retirement of `dbnary-synonym`).

## What was actually done in this session

1. Branch + design doc committed (commit `c31b514`).
2. Detector: `scripts/eval/find_pp_productive_verbs.py`. 1348 PP-productive
   verbs in `words-fr.csv` (top-3 by total PP frequency: faire / partir /
   poindre).
3. Hand-authored DPO corpus at `data/lora_dpo_iter13/`:
   80 verbs × 2 chosen variants = 160 pairs. Splits: 64 train (128 pairs),
   8 valid (16 pairs), 8 test (16 pairs). All 160 chosen entries pass
   `validate_lemma_clue` and `MorphologyIndex.inflect` to clean PP forms.
   Combined with iter12's 180 pairs at `data/lora_dpo_iter13_combined/`
   (308 train / 36 valid / 36 test).
4. `scripts/clue_generation/lora_iter13_dpo.yaml` — diff vs iter12: combined
   data dir, output → `models/lora-clue-v6`, `iters: 320`, `fuse: false`.
5. Defensive PP+DObj skip in `scripts/eval/inflect_clue.py` (new flag
   `pp-only-skipped`) + `scripts/clue_generation/build_surface_clues.py`
   (force-routes the flag to dropped). 4 new tests in `test_inflect_clue.py`.
   Full eval suite green (100/100).
6. Iter13 DPO smoke test (5 iters) — confirmed config + DPO loss compile.
7. Iter13 DPO full run kicked off; **stopped early** at iter ~209/320 to
   probe overfitting (train loss reached 0.02-0.1, suggestive of
   memorisation on the small corpus).

## Important deviation from the design doc

The spec said "resume from v3 (SFT)" *and* implicitly replace iter12's
gains. Instead, I merged iter12's 180 pairs with iter13's 160 into a
**single combined DPO run** still resuming from v3. This preserves
iter12's stem-leak / self-reference fixes alongside iter13's PP-frame
preference. One training run produces v6 (not v6 from iter12 → then v7
from iter13).

Reasoning: stacking DPOs by resuming from a previous DPO output compounds
deviation from the SFT distribution, which the playbook implicitly
discourages. Combining preference corpora and running one DPO from the
SFT base is the clean form.

## Iteration log

### iter13 (v6) — 2026-05-05 21:00

- Corpus: iter12 180 + theme-PP 128 = 308 train.
- 320 iters, lr 1e-6, β 0.1, sigmoid, resume from v3 SFT.
- 100-verb held-out PP eval: **77.9% self-rated** (67/86), 86 rated rows,
  7 placeholder. y=59, b=16, n=11.
- Failure-theme tally:
  - **Reflexive infinitive emitted**: relever, mouvoir (2 n's).
  - **Wrong-sense drift**: recourir, déférer, exister, briller, tomber (5).
  - **Compound `verb et verb`** (inflater limit, b's): ratifier, surmonter,
    équiper, gérer, confisquer.
  - **Mood/gender mis-pick** (script bug + inflater edge): succéder, convenir.
  - **Broken French**: déroger.
- Result: just under 80% gate. Ship-ish but the failures are addressable.

### iter13.1 (v6_1) — 2026-05-05 21:35 — *transitional, not promoted*

- Strategy: continue-train from v6 with theme-1 (reflexive avoidance,
  10 pairs) added on top. 100 iters at half lr (5e-7).
- 100-verb eval: 83.8% self-rated (67/80), 80 rated, 14 placeholder.
- **DPO-on-DPO compound drift**. Theme1 hit its targets (relever, mouvoir,
  succéder, tomber, convenir flipped n→y/b) but the small-corpus
  continuation also drifted unrelated verbs into nominal-shape outputs
  (`bouger → "Changements d'emplacement"`, `ratifier → "Approbation
  formelle"`, `traverser → "Franc-tireur"`) and one outright hallucination
  (`recourir → "hasOwnProperty"`).
- y count unchanged at 59 — gate clearance is a denominator-drop artifact,
  not a real quality lift. Rolled back; not promoted.
- New theme-2 identified: **POS adherence** (verb prompt → noun output).

### iter13.2 (v6_2) — 2026-05-05 22:05

- Strategy: clean retrain from v3 SFT base with the full theme-augmented
  corpus (iter12 + iter13 + theme-1 reflexive + theme-2 POS). 16 new
  pairs total. 340 iters at original lr 1e-6. Replaces both iter13 and
  iter13.1 — same-arch retrain, richer corpus.
- 100-verb eval: **85.1% self-rated** (74/87), 87 rated, 13 placeholder.
  y=66, b=16, n=5. Both Theme 1 and Theme 2 wins preserved.

### iter14 (v7) — 2026-05-06 07:36

- Strategy: hand-author 1000 PP-adj-productive verb (y, n) pairs as a
  full DPO corpus replacement. Combine with iter12+iter13 themed pairs
  for 1108 train / 136 valid / 136 test. Resume from v3 SFT, 600 iters
  at lr 1e-6. Wrapper `scripts/clue_generation/train_dpo.sh` forces -u
  for mid-train val flush.
- 100-verb eval (same seed, original PP-productive pool, apples-to-apples):
  **90.1% self-rated** (77.5/86), 86 rated, 14 placeholder.
  y=71, b=13, n=2. **+5pp over iter13.2.**
- Val curve cleanly visible mid-train post-fix:
  iter 1: 1.141 → iter 50: 0.035 → iter 100: 0.024 → iter 600: 0.017.
  Val saturated by iter 100; later improvement is largely memorization
  of the authored corpus's style (since I authored both train and valid).
  The held-out 100-verb eval is the trustworthy metric.
- Theme wins: briller, tomber, exister, boucher, grouper, éclore all
  flipped from problem rows to y. exploiter, recourir, conjurer borderline
  → y.
- New theme identified — **Theme 4: self-reference re-emergence**. 3
  verbs flipped y → self-reference (réinstaller, étouffer, peser →
  emit the lemma itself as clue). The 1108-pair corpus diluted iter12's
  self-reference fix (180/1108 = 16% iter12 vs 180/308 = 58% before).
  Targeted addition staged at `data/lora_dpo_iter14_theme4_self_ref/`
  (12 pairs); ready for an iter14.1 polish if user wants further lift.

### Production pipeline run (iter14) — 2026-05-06 07:48 *(in flight)*

- `bash scripts/clue_generation/run_production.sh` with
  `GEN_ADAPTER=models/lora-clue-v7`. X=5000 lemmas, threshold 0.65,
  filter v5. Backup of pre-iter14 production CSVs at
  `data/eval/production/backup_pre_iter14/`.
- ETA ~70 min generation + ~10 min surface build / merge.
- After merge, runtime guard `pytest scripts/eval/test_runtime_csv_pleonasms.py`
  must pass before opening PR-B.

### iter15 corpus-correction experiments — 2026-05-06 (all regressed; iter14 stays winner)

Post-iter14 audit (`scripts/eval/audit_iter14_corpus.py`) found 436/1000
n_clues (43.6%) were `PP_BRIDGE` shape (`Mettre en X` etc.) — i.e.
*PP-friendly framings* miscategorized as bad foils. Three correction
attempts:

- **iter15a (drop noisy):** 569 train (327 clean iter14 + 308 themed).
  16 layers, 400 iters. Result: **87.8%**, ↓ 2.3pp vs iter14. Verb
  coverage drop (1000 → 327) hurt more than foil cleanliness helped.
- **iter15b (replace noisy → self-ref):** 1108 train, all 673 noisy
  rejected replaced with `lemma.capitalize()`. **12 layers** (throttled
  for system responsiveness), 600 iters. Result: ~78%, ↓↓. Self-ref
  overweighting (67% of foils) caused **verbose paraphrase regression**
  (20 too-long outputs) — model elongated to differ from lemma; reduced
  num_layers couldn't override SFT-learned brevity.
- **iter15c (same corpus, full 64-layer LoRA, 100 iters):** Diagnostic.
  Result: 80.8%. Brevity restored (single-word synonyms) but **semantic
  drift** (model picks wrong-meaning synonyms to maximize lemma-distance:
  `entremettre → Troublée`, `relever → Surmontée`, `dériver → Égarée`,
  `mouvoir → Ébranlée`). Confirms the corpus is the root cause; layer
  reduction in 15b was secondary.

Lesson: PP_BRIDGE foils, while imperfect, contribute positive
verb-coverage signal. A real iter15 would author categorically-bad foils
that *preserve meaning anchoring* — `verb + DObj` using meaning-related
nouns, not random self-reference. Logged as Theme 5 followup.

### Worker change (dbnary-synonym retirement) — 2026-05-06 07:50

- `grid/worker/.../ExportWordsCommand.kt` `DEFAULT_CANDIDATE_PRIORITY`
  changed from `"curated,dbnary-synonym"` →
  `"curated,command-r-lora-v7-iter14"`. Existing integration tests pass
  the priority string explicitly via `--candidate-priority`, so they
  continue to exercise the dbnary-synonym path; only the production
  default flips. The dbnary-synonym SQL derivation and worker subcommand
  stay in place for ad-hoc revival per ADR-0024-followup.

## Open question at session pause

**Which iter13 checkpoint to promote?** Saved adapters at iters 50, 100,
150, 200. Train loss curve dropped fast (0.6 → 0.1) by iter ~50 then
oscillated. Without a flushed val curve from the killed run, I'm probing
each checkpoint via `mlx_lm_lora.train --test` to measure DPO test loss
and test accuracy on the held-out 36-pair test split. Best-test-loss
wins; in-progress at session pause.

If all 4 checkpoints look similar on test → iter 50 (most conservative,
furthest from overfit) is the safe promote.

## Things to mention

- The `dbnary-synonym` lane is currently the source for ~30% of nouns'
  clues. Retiring it (option C) without iter13 lifting noun coverage
  will cause a temporary placeholder regression on those nouns until
  the next iter fills them. User accepted this risk explicitly.
- The `_DOBJ_DETERMINERS` set in `inflect_clue.py` includes `du` (masc-sg
  partitive). `de la` / `de l'` partitives are NOT in the set because
  they wear `de` first, which makes the surface PP-friendly anyway
  (`Couvert de lait` → `Couverte de lait` is a state). If a clue like
  `Boire du vin` ends up in the residual after iter13, the skip will
  fire. Reasonable.
- ADR-0024 deprecation will need an actual ADR doc, scoped as a
  follow-up after iter13 ships. Out of scope for this PR per design §11.
- The runtime guard `test_runtime_csv_pleonasms.py` was kept green
  through all changes. When the new `words-fr.csv` lands, re-run
  `pytest scripts/eval/` before opening PR-B.

## User-flagged guidance (2026-05-05, mid-session)

- **Reflexive clue heads ARE valid in mots-fléchés.** `S'esclaffer → Rire`
  is a legal pattern. The `(se)` parenthetical convention exists for
  verb-form answers (`Bouger (se) → déplacer` meaning the reflexive sense)
  but is unrelated to PP-as-adjective surfaces.
- **Reflexive ≠ non-reflexive in meaning.** `Se tromper` (to be mistaken)
  is not `Tromper` (to deceive). Similarly `Se baigner` (to swim) ≠
  `Tremper` (to soak). The Theme1 DPO corpus should keep the chosen
  synonym **meaning-preserving** for the PP-as-adj sense — for DPO this
  is a soft preference (relative ranking, not absolute matching), but
  for any SFT seeds in future iters it is a hard requirement.
- **The load-bearing safety net is the inflater-side reflexive skip**,
  not the corpus DPO. The skip catches residual `Se X` clues that
  PP-target by emitting `pp-reflexive-skipped` → placeholder, never
  `*Se X-ée` shipped.
- **mlx-lm-lora val output is buffered when stdout is redirected**.
  The trainer's `tqdm.write()` calls land in Python's block-buffered
  stdout when redirected to a file; `Val loss` lines only appear at
  end-of-run, defeating any mid-train val-loss-based early-stop. Fix:
  run with `python -u`. The wrapper at
  `scripts/clue_generation/train_dpo.sh` always passes `-u`. Use it
  for all future iter*.x runs.
- **Standard mots-fléchés clue parenthetical conventions our pipeline
  doesn't yet honor** (out of iter13 scope; flagged for iter15+):
  - `(se)` / `(s')` on clue text — reflexive-sense marker
    (e.g. `Bouger (se) → déplacer` for the `se déplacer` reading).
  - `(qqch)` / `(qqn)` / `(qlq ch)` — transitive direct-object marker
    (e.g. `Casser (qqch) → briser`, `Voir (qqn) → rencontrer`).
  These markers are absent from the curated, SFT, and DPO corpora today.
  The inflater's tokenizer survives them (parens are non-alpha tokens,
  not picked as heads), so adopting them later won't require an
  inflater rewrite — just corpus + validator updates.

## Files touched

```
docs/superpowers/specs/2026-05-05-pp-form-corpus-reframing-design.md      [new]
docs/eval/pp-form-session-2026-05-05.md                                    [new, this file]
scripts/eval/find_pp_productive_verbs.py                                   [new]
scripts/eval/inflect_clue.py                                                [edit, +52]
scripts/eval/test_inflect_clue.py                                           [edit, +60]
scripts/clue_generation/build_surface_clues.py                              [edit, +8]
scripts/clue_generation/lora_iter13_dpo.yaml                                [new]
scripts/clue_generation/lora_iter13_dpo_smoke.yaml                          [new — can delete after eval]
data/eval/pp_productive_verbs.csv                                           [new, generated]
data/lora_dpo_iter13/build_corpus.py                                        [new]
data/lora_dpo_iter13/{train,valid,test}.jsonl                               [new, generated]
data/lora_dpo_iter13_combined/{train,valid,test}.jsonl                      [new, generated]
models/lora-clue-v6/{0000050..0000200}_adapters.safetensors                 [new, gitignored]
```

## Pending after iter13 promotion

- Re-run `run_production.sh` with `GEN_ADAPTER=models/lora-clue-v6`.
- Rebuild surface clues with PP+DObj skip in place; confirm
  `pp-only-skipped` count drops materially vs. baseline (target: ≥50%
  of the ~410 baseline gets rescued by iter13 framings).
- Hand-rate 100 PP-only surfaces; gate ≥80% self-rated.
- If gate clears: drop `dbnary-synonym` from `findTopBySourcePriority`,
  re-export `words-fr.csv`, append iter13 row to
  `docs/eval/clue-gen-v0.md`.
- If gate fails by ≤5pp: escalate to iter14 (SFT corpus injection).
- If by >5pp: stop, hand back. Failure is mechanistic.
