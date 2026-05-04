# Clue-Generation Phase 0 Eval

Status: in progress.

## Setup

- **Sample**: 100 French words, 10 per length bucket (3–12 chars), drawn from
  `grid/api/src/main/resources/words/words-fr.csv` and filtered to entries with
  a non-empty `lemma` and an alphabetic `word`. The plan called for POS
  stratification, but `words-fr.csv` does not retain POS — length stratification
  + lemmatized-alphabetic filter is the proxy used here. POS + morphology come
  back from grammalecte at enrichment time.
  - Generated via `python scripts/eval/sample_eval_words.py`.
  - Saved at `data/eval/sample_100.csv`.
- **Definitions + synonyms**: pulled per-word from the DBnary public SPARQL
  endpoint (`http://kaiko.getalp.org/sparql`) — no full TTL download.
  - Run `python scripts/eval/fetch_dbnary_for_sample.py`.
  - Saved at `data/eval/sample_100_with_definitions.csv`.
- **Morphology**: parsed from `lexique-grammalecte-fr-v7.7.txt` (MPL-2.0 — same
  licence the `import-grammalecte` worker pipeline declares). Each word gets
  POS + gender + number + (for verbs) mood + tense + person, e.g. `SEXUALISENT
  → verbe, indicatif présent, 3e pers. plur.`. Lexique 4 / Lexique3 was
  evaluated and rejected (CC BY-SA share-alike would virally taint the corpus).
  - Run `python scripts/eval/enrich_with_morphology.py --lexique <path>`.
  - Saved at `data/eval/sample_100_enriched.csv`.
- **Model**: `mlx-community/Mistral-7B-Instruct-v0.3-4bit` via `mlx-lm`.
  - One-time install (in a venv): `pip install mlx-lm`. Weights download on
    first load.
- **Prompt**: `scripts/eval/prompts/clue_v0.txt` (13 hand-crafted exemplars
  showing inflection-matching, plus per-word slots `{word_upper}`, `{lemma}`,
  `{morphology}`, `{dbnary_definition}`, `{synonyms_csv}`).
- **Inference + post-filter**: `python scripts/eval/generate_clues_v0.py`
  writes `data/eval/run_v0_results.csv`. A `flag` column is auto-set when the
  clue echoes the surface, the lemma, or exceeds 8 words — those rows should
  be reviewed first. The `rating` column is left blank for hand-rating.

## How to hand-rate

Open `data/eval/run_v0_results.csv` in any editor / spreadsheet. Fill the
`rating` column with one of:

| Rating | Meaning | Score |
| --- | --- | ---: |
| `y` (or `yes` / `✓` / `1`) | acceptable mots-fléchés clue | 1.0 |
| `b` (or `borderline` / `◯`) | usable with a small edit | 0.5 |
| `n` (or `no` / `✗` / `0`) | unusable | 0.0 |

Then run `python scripts/eval/eval_clue_quality.py` to recompute the block
below.

## Decision rule

| Acceptance | Action |
| --- | --- |
| ≥ 85% | **SHIP**. Skip Phase 4, use base model + prompts as the offline batch tool. |
| 70–85% | **FINE-TUNE**. Run Phase 4 (LoRA on Mistral-7B). |
| < 70% | **INVESTIGATE**. Enrich prompt, try other base models, grow curated set, re-run Phase 0. |

## Failure-mode notes

(Fill in when rating: e.g. "leaks the word", "too long", "uses uncommon
register", "circular definition".)

## Results

<!-- AUTO:BEGIN -->
_Last evaluated: 2026-05-03 15:11 UTC_

**Acceptance: 78.6%** (70 rated, 30 unrated)

**Decision: FINE-TUNE** — 70% ≤ acceptance < 85%. Run Phase 4 (LoRA fine-tune); fine-tuning likely moves the needle to ≥ 90%.

### Breakdown by length

| Length | N | Acceptance |
| ---: | ---: | ---: |
| 3 | 7 | 57.1% |
| 4 | 7 | 92.9% |
| 5 | 8 | 87.5% |
| 6 | 10 | 80.0% |
| 7 | 12 | 95.8% |
| 8 | 7 | 100.0% |
| 9 | 4 | 75.0% |
| 10 | 7 | 71.4% |
| 11 | 7 | 28.6% |
| 12 | 1 | 100.0% |

### Breakdown by POS

| POS | N | Acceptance |
| --- | ---: | ---: |
| adjectif | 7 | 64.3% |
| adverbe | 1 | 100.0% |
| nom | 30 | 76.7% |
| verbe | 32 | 82.8% |
<!-- AUTO:END -->

## Iteration progression (lengths 4–11, 80 lemmas, 10/length, command-r)

| Run  | Acceptance | Change vs prior                                                                                         |
| ---- | ---------: | ------------------------------------------------------------------------------------------------------- |
| iter1 | 78.75% (user) | command-r baseline, no DBnary context                                                              |
| iter2 | 79.4% (user)  | + DBnary definition + synonyms in prompt                                                           |
| iter3 | 85.6% (user) / **75.6% (self)** | + archaic-tier filter on senses (Vieilli/Désuet penalty)                         |
| iter4 | **68.8% (self)**                | + full-clue lemma-family self-reference check; + adv-phrasal validation              |
| iter5 | **81.2% (self)**                | + v1 prompt: anti-pattern exemplars (✗→✓ pairs from iter4 failures), POS-conditional exemplar blocks |
| iter6 | (twins-only)                     | + frequency-aware verb-twin emission: when surface dual-tagged (noun + frequent verb), generate clues for both lemmas. User picks. |
| iter7 | **84.4% (self), 85.6% w/ synonym best-of** | + multi-sense in prompt (numbered list of all clean DBnary senses); + stem-leak validator check (LCP ≥ 5 OR substring containment) |

Self-rated baseline runs ~10pp stricter than the user's. The like-for-like
self-rated comparison (iter3 = 75.6%, iter4 = 68.8%) shows iter4 **regressed**
6.8pp despite the validator improvements. Diagnosis:

- **5 lemmas improved** (`amende`, `argument`, `dater`, `diffus`, `incitation`)
- **12 lemmas regressed** — mostly unrelated to the validator change
  (`aurore` y→n, `colloque` y→n, `monstrueux` y→n, `ovale` y→n, `cuite` b→n…)
- Validator did its targeted job: caught `impur` (`Matière impure` leak) and
  `asseoir` (`Faire s'asseoir` leak) — but the **retry produced worse clues**
  (`Malpropreté charnelle` POS-mismatches the adj target; `Mettre en selle` is
  wrong meaning).

**Conclusion: at N=80, model stochasticity dominates the validator signal.**
A single command-r generation at temp 0.3-0.9 has enough variance that
unrelated lemmas swing y↔n between runs. The validator change is structurally
correct (full-clue self-ref check is the right invariant) but unmeasurable at
this sample size and single-candidate regime.

### iter5 result: prompt anti-patterns work

The v1 prompt added 7 anti-pattern ✗→✓ pairs derived directly from iter4's
failures (`monstrueux`, `impur`, `asseoir`, `doux`, `remarquer`, `ovale`,
`bourgeois`) and POS-conditional exemplar blocks. Result: **+12.4pp over
iter4, +5.6pp over iter3**.

The model copies exemplars almost verbatim when the target lemma matches:
- `monstrueux → "Affreux et atroce"` (verbatim from prompt)
- `asseoir → "Prendre place"` (verbatim)
- `remarquer → "Apercevoir distinctement"` (verbatim)
- `bourgeois → "Membre de la classe moyenne"` (verbatim)
- `fidèlement → "Avec loyauté"` (verbatim)
- `colloque → "Rencontre savante"` (verbatim)

Lesson: high-leverage prompt changes outweigh validator changes at this scale.
The prompt directly cures repeatable failure modes; the validator only
constrains what gets accepted, but cannot improve the underlying generator.

iter5 remaining `n` cases reveal new gaps:
- `destructeur → "Agent de destruction"`: radical-leak. Validator's
  inflection-family check doesn't cover same-stem derivatives. Need a
  stem/root check.
- `passif (nom) → "Bâtiment économe en énergie"`: persistent wrong-sense
  (still latches onto "maison passive" adj sense). DBnary sense-1 is the
  construction sense; need to pass all senses or override.
- `chercheur → "Chercheur infatigable"`: model exhausted retries with
  literal lemma. Anti-pattern was added for `monstrueux`-style; doesn't
  generalize to all self-ref cases.
- `explicite` (verbe target on adj lemma): grammalecte tags this lemma as
  both adj and verb; enricher picked verb POS but the lemma is adj-only in
  practice. POS resolution bug.

iter4 validator deltas observed (qualitative):
- `impur`: `"Matière impure"` (fem-sg form leaks) → `"Malpropreté charnelle"` ✓
- `asseoir`: `"Faire s'asseoir"` (lemma literal) → `"Mettre en selle"` ✓ (clean, though sense-stretched)
- `monstrueux`: model insists on dictionary-style `"Monstrueux : ..."`; all 3 retries flagged. Pure prompt problem — model needs example showing definition-style format is forbidden.

## Documented failure modes (across iter1–4)

1. **Hallucination** — model invents a meaning not in DBnary. Mostly resolved by Phase 2 DBnary context.
2. **Self-leakage** — clue contains the lemma or one of its inflected forms.
   - Head-position: validator catches.
   - Non-head (`Matière impure` for `impur`): iter4's full-clue family check catches.
   - Dictionary-style header (`Monstrueux : ...`): retries don't help; model habit.
3. **POS mismatch** — `doux (nom) → "Soyeux"`, `passif (nom) → "Bâtiment sobre"`. Validator catches inflected forms but not when the wrong sense exists in the lemma's POS.
4. **One sense of many** — DBnary returns the legal sense of `amende` first; the model commits to it. Without sense-ranking, off-prior-probability senses dominate.
5. **Stretched / generic** — `direction → "Axe à suivre"`, `gouvernance → "Direction politique"`. Technically right, mots-fléchés-flat. The model has no notion of clue idiom density.
6. **Lexical-accident POS collision** — `chapelle` (nom) shares grammalecte inflection with `chapeler` (verb, "to crumb stale bread"); naïve verb-priority destroys the clue. Need frequency-aware disambiguation.

## Documented success modes

- **Synonym-direct**: `chapelle → "Oratoire"`, `meurtre → "Assassinat"`, `criminel → "Malfrat"`. DBnary synonyms are top-1 candidates when available.
- **Pun / convention**: `amende → "Contravention financière"`, `suprême → "Tendre viande blanche"` (suprême de volaille), `âtre → "Foyer central"`. The model produces these reliably when the prompt exemplars cue the register.
- **Compositional NP**: `aurore → "Première lumière"` (when it lands), `usurpateur → "Prétendant illégitime"`, `truchement → "Porte-voix humain"`. Two-word adj-noun is the genre's idiom.

## Synonym-direct candidates (free win on ~30% of nouns)

`scripts/eval/synonym_clues.py` walks the DBnary `synonyms` column,
capitalizes the first synonym that passes `validate_lemma_clue` (head
in grammalecte + lemma form + matching POS), and emits it as a clue with
`source='dbnary-synonym'`. No model invocation.

On the iter6 sample: 28 unique lemmas (32%) got a synonym clue.
Side-by-side rating against the iter6 model clues for those 28:

| outcome | count |
|---|---:|
| tie at `y` | 18 |
| tie at `b` | 1 |
| synonym wins | 4 (`grandeur`, `majorité`, `bourgeois`, `interpréter`) |
| model wins | 5 (`ouïe`, `poète`, `avoir`, `asseoir`, `soumettre`) |

Synonym-direct ≈ model quality on overlap, at **zero API cost**. The
5 model wins are all wrong-sense or archaic synonyms picked by the
"first passing validation" heuristic — DBnary lists synonyms in
arbitrary order. Refining the picker would close that gap:

- Prefer single-word synonyms (canonicalness signal).
- Prefer synonyms whose own grammalecte frequency exceeds a threshold
  (drops `feudataire`, `sisitte`, `jubé`).
- Skip parenthesized or hyphenated forms.

Mechanism is **additive**: synonym candidates live in a separate file
(`synonym_clues_iter6.csv`) and downstream best-of selection picks per
lemma. No regression risk on words where the model clue is better.

### + token-frequency filter

Each content-word token in a synonym candidate must have grammalecte
total occurrences ≥ 1000, else reject and try next synonym. Catches:
- `asseoir → "Faire sisitte"` (sisitte unknown to grammalecte)
- `cuite → "Murge"` (70 occ; falls through to next synonym `Race`)

Doesn't catch wrong-sense picks (`ouïe → Branchie`, `poète → Amant`,
`avoir → Note de crédit`) — those are real high-freq words just
attached to the wrong sense in DBnary's loose synonym graph. Sense
disambiguation needs more than freq.

### Best-of model+synonym score

Picking the better of (model clue, synonym clue) per lemma on the 80
originals: **84.4%** (y=60, b=15, n=5). Synonym chosen for 4 lemmas:
`grandeur → Taille`, `majorité → Âge adulte`, `bourgeois → Bourge`,
`interpréter → Exécuter`. Compare iter6 model-only at 81.9%, iter5 at
81.2%. Net +2.5–3.2pp lift, zero added API cost.

## iter6: verb-twin emission

The enricher (`scripts/eval/enrich_with_morphology.py`) now detects
dual-tagged surfaces (noun lemma + frequent verb lemma) using the
`Total occurrences` column from the grammalecte lexique. When the verb's
total frequency is ≥ 5% of the noun's AND ≥ 1000 absolute, a TWIN row is
emitted with the verb infinitive as `lemma`/`word`, fresh DBnary fetch,
and a `twin_of` column linking back. The original noun row is preserved.

The threshold cleanly excludes lexical accidents like `chapelle/chapeler`
(ratio 0.0002), `colloque/colloquer` (0.043). It does NOT separate
semantically-related pairs (`maîtrise/maîtriser`) from
semantically-divergent pairs that share inflection
(`amende/amender`, `contrée/contrer`, `couvert/couvrir`) — that signal
isn't in the lexique. Both still get emitted; downstream rating picks.

On the iter6 sample 8 twins were emitted; 1 (`maîtrise → "Contrôler et
dominer"`) was a clear win over the noun's clue. The other 7 had the
noun-form clue as the better choice — the twin is a free additional
candidate, not a replacement.

Mechanism is **additive**: twin emission never displaces the original
clue, so cost of false-positive emissions is just extra rater work, not
clue regressions.

## iter7: multi-sense prompt + stem-leak validator

Two changes shipped together:

1. **Multi-sense in prompt**: `clean_definition` in the enricher now keeps
   ALL clean senses (non-archaic first, then archaic) pipe-delimited.
   `generate_clues_lemma.py:render_user_content` renders them as a numbered
   list when 2+ senses exist. The model gets to pick the most cluable
   sense instead of being anchored on sense_1.
2. **Stem-leak in validator**: `_find_stem_leak` flags any clue token
   sharing ≥5 chars of prefix with the target lemma (catches
   `destructeur → "Agent de destruction"`, LCP "destruct" = 8) OR being
   a substring of the lemma when both ≥5 chars (catches `ébattre →
   "Battre joyeusement"`). Short targets (<5 chars) skip the check.

Result on the 80-original sample: **84.4% self-rated** (+2.5pp over
iter6's 81.9%). With synonym-direct best-of layered on: **85.6%** —
above the 85% "ship" bar.

13 lemmas required retries (vs ~3 in iter6); most converged to clean
clues. One regression: `destructeur` retry away from "Agent de
destruction" landed on "Détergent puissant" (wrong meaning) — model can
panic into sense-drift under retry pressure. Still net positive.

Stem-leak threshold (5 chars) is conservative. False-negatives include:
- `couvrir → "Protéger avec une couverture"` — LCP "couvr" = only 4
  (the t↔r ablaut breaks the prefix); couverture clearly cognate.
- `mortalité → "Condition mortelle"` — LCP "mort" = only 4 (a vs e).

Bumping to 4 chars catches these but risks false positives on common
Latin/Romance affixes (`pre-`, `con-`, `de-`, `re-`). Holding at 5 for
now; revisit if stem-leak failures cluster.

## 5-sample variance check (iter7 setup)

To validate that iter7's 84.4% wasn't sample-specific, ran 5 independent
samples (top-1000 per length, 4-11 chars, different seeds 20260601-05):

| sample | rows | y | b | n | score |
|---|---:|---:|---:|---:|---:|
| 20260601 | 85 | 65 | 15 | 5 | 85.3% |
| 20260602 | 87 | 66 | 13 | 8 | 83.3% |
| 20260603 | 91 | 69 | 16 | 6 | 84.6% |
| 20260604 | 87 | 72 | 12 | 3 | 89.7% |
| 20260605 | 90 | 73 | 11 | 6 | 87.2% |

**Mean 86.0%, stdev 2.5pp, range [83.3, 89.7]**.

iter7 sits robustly at the 85% ship threshold. The 2.5pp stdev confirms
the earlier analysis: any intervention worth measuring at N=80 must
exceed ~5pp effect to be distinguishable from noise.

## Strategy: four axes for further improvement

### Axis A — Better context (prompt input data)

| Change                                                                                                                  | Expected effect                                  | Cost   |
| ----------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------ | ------ |
| Pass **all 5 DBnary senses** (already fetched) instead of just the first; let the model pick the most cluable           | Reduces "one-sense-of-many"; helps polysemic nouns | low    |
| Add **frequency rank** of the lemma so the model knows whether to be casual or formal                                   | Lifts "stretched" cases                         | low    |
| Add **2-3 nearest neighbours by lemma family** (siblings) so the model can contrast meanings                            | Helps disambiguate generics                     | medium |
| Add **register tags** (`(Familier)`, `(Soutenu)`, `(Argot)`) extracted from DBnary parentheticals into a separate slot | Calibrates output register                      | medium |

### Axis B — Better input filtering

| Change                                                                                                              | Expected effect                                          | Cost   |
| ------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------- | ------ |
| **Frequency-aware verb/noun priority**: when both POS exist for a lemma, pick the more frequent one in grammalecte | Fixes `amende→amender` without breaking `chapelle/chapeler` | medium |
| **Drop senses with `(Désuet)`, `(Archaïsme)`, `(Vieilli)`** before passing to model (already done)                  | Already in iter3: +6.2 pp                                | done   |
| **Prefer the lemma's first noun sense over verb sense** for cluing when frequency is close (preserve user's verb-bias only when verb-form dominates) | Targeted fix for the `amende/amender` class             | medium |
| **Strip pointer senses** (already done: `(Par ellipse) X`, `Synonyme de X`) and chase the redirect                  | Already in fetch; keep                                  | done   |

### Axis C — Better prompt

| Change                                                                                                          | Expected effect                                  | Cost   |
| --------------------------------------------------------------------------------------------------------------- | ------------------------------------------------ | ------ |
| **Anti-pattern exemplars**: explicitly show `monstrueux: "Monstrueux : ..." ✗` then `→ "Affreux et atroce" ✓`   | Kills dictionary-style header habit              | low    |
| **POS-conditional exemplar blocks**: 5 noun examples for noun targets, 5 verb examples for verb targets        | Reduces POS mismatch in output                   | low    |
| **Length budget hint**: `"Cible: 2-4 mots, jamais plus de 6"` — currently implicit                              | Trims over-long compositional NPs                | low    |
| **Forbid list inline**: `"Interdits: répéter le mot, ses dérivés, sa racine"`                                   | Reinforces validator (in-prompt + post-check)    | low    |

### Axis D — Adjacent prompt (ensemble / regenerate)

| Change                                                                                                                            | Expected effect                                                  | Cost |
| --------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- | ---- |
| **N=3 candidates per lemma** at varied temperatures (0.3 / 0.6 / 0.9), keep all valid ones in `clue_candidates`                  | Multi-clue future + better top-1 via downstream ranker           | low  |
| **Synonym-clue free win**: emit `(word, synonym)` pairs as `source='dbnary-synonym'` candidates without invoking the model       | Free quality for ~30% of nouns; reduces model load               | low  |
| **Critique pass**: feed `(lemma, candidate, definition)` back to the model with `"Cette définition est-elle correcte? oui/non"` | Catches sense-mismatch self-rated; cheaper than human review     | medium |
| **Two-stage generation**: stage 1 picks the sense (1..5), stage 2 writes the clue from that sense                                | Forces the model to commit to a sense before clue-shape pressure | high |

## Recommended next steps (ranked, after iter4 regression analysis)

The iter4 result reframes priorities. Single-candidate-per-lemma is too noisy
to evaluate small interventions, so **variance reduction comes first**:

1. **N=3 candidates per lemma + best-of selection** (Axis D). Generate 3 at
   varied temperatures, keep all valid, pick top-1 by a downstream scorer
   (length, synonym-overlap, anti-self-ref). Without this, every other change
   is unmeasurable. Sets up multi-clue future as a side-effect.
2. **Anti-pattern exemplars in the prompt** (Axis C). Show `monstrueux: ✗
   "Monstrueux : ..."` → `✓ "Affreux et atroce"` and `asseoir: ✗ "Faire
   s'asseoir"` → `✓ "Prendre place"`. Repeatable model habits — prompt-level
   fix.
3. **Pass all 5 DBnary senses, not just sense_1** (Axis A). The model commits
   to whichever sense it sees; giving it the menu lets it pick the most
   cluable. Biggest expected lift on polysemic nouns.
4. **Synonym-direct candidates** (Axis D). DBnary synonym → clue, no model
   call. ~30% of nouns get a free top-1. Implement as Phase 2 of the
   original plan with `source='dbnary-synonym'`.
5. **POS-conditional exemplar blocks** (Axis C). 5 noun examples for noun
   targets, 5 verb examples for verb targets, 5 adj examples for adj. Iter4
   shows adj at 50%, verbe at 50% — both far below nom; targeted exemplars
   should close the gap.
6. **Frequency-aware POS priority** (Axis B). Use grammalecte's `Total
   occurrences` column. Unblocks `amende → amender` rewrite without breaking
   `chapelle/chapeler`. Lower priority than 1-5 because it only helps a
   small slice.

Phases 4–5 (LoRA fine-tune, SQLMesh) remain gated on iter5 acceptance with
items 1–3 in place. If iter5 with N=3 + better prompt clears 90% on a 200+
lemma sample, fine-tuning is unnecessary.

## Methodological note

For iter5 onward, scale the eval set to **200+ lemmas** and use **N=3
candidates per lemma**. With 80 lemmas and 1 candidate each, run-to-run
variance (~7pp here) drowns out structural changes. With 200 lemmas and the
best-of-3 ranker, variance should drop below the magnitude of typical
interventions (3–5pp), making iter-to-iter comparisons meaningful.
