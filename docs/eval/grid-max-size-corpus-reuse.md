# Grid Max-Size Exploration — Corpus Reuse & Sense-Aware Dedup

Status: research notes, 2026-05-31.

Not an ADR. This documents an exploration of how grid-generator constraints
(corpus shape, dedup keying, layout knobs) interact at large grid sizes far
beyond the production daily-puzzle defaults (15×12 today). The aim was to
understand the structural ceilings, the cost of each rule, and to leave a
recipe for repeating the experiment.

## Question

How big a structurally-valid grid can the existing bitmask-CSP generator
(ADR-0039) fill if we let the corpus and the dedup rule breathe, and what
clue-corpus work would shipping such a grid require?

## TL;DR

The corpus is not the wall. The wall is the **dedup contract** the CSP
enforces, combined with the layout's `BlackCellLayout` density. Within a
≤2-minute wall budget per call, sense-aware dedup (the "principled" rule —
distinct surfaces × distinct DBnary senses) tops out at ~80×64 (about 2,000
slots) with the current `BlackCellLayout`. Surface-only dedup with an
unlimited-reuse short-word carve-out can push much further (150×120, 7,000
slots) but allows the same lemma to recur tens of times, which is not
playable. The "right" knob set for the principled rule is
`blackRatio = 0.26`, `BASE_BUDGET_BACKTRACKS = 1000`, `MAX_RESTARTS = 50_000`,
`per-attempt` extended for big-area tiers. Pushing past 80×64 needs either
weakening the lemma rule or changing the layout strategy — not more corpus.

## Why this matters

Two practical questions sit behind it:

1. The daily-puzzle generator already cycles a clue cooldown table to avoid
   showing the same clue twice. The cooldown rule is "no clue twice in a
   row," which is unrelated to the in-grid dedup. Knowing how the in-grid
   rule scales tells us how much room we have if we ever wanted to ship a
   bigger Sunday-grid format (50×50, 60×60).
2. Future "sense rotation" features (e.g. multi-clue per lemma surfacing
   different senses across days) need to know whether the runtime corpus
   has the sense diversity to support cross-grid variety. The numbers below
   give an honest estimate.

## Corpus reality (three layers)

The corpus has three distinct shapes, and confusing them produces wrong
answers about "how big".

| Layer | Source | Folded-surface count | Notes |
|---|---|---:|---|
| Production runtime | `grid/infrastructure/src/main/resources/words/words-fr.csv`, filtered to non-blank `clue` | **25,240** | What the live generator sees. Drop is silent in `CsvWordRepository.toWordWithFreq`. |
| CSV corpus (all rows) | Same CSV, no blank-clue filter | 113,240 | 88k inflections of common lemmas missing clues — `MERE`, `NORD`, `ROSE`, `PURE`, etc. ship without a surface clue. |
| Grammalecte universe | `~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt` filtered for non-desuet, A-Z foldable | **321,501** | The full surface-form lexicon. Production CSV is a subset gated on clue presence. |

The grammalecte file holds 515,693 raw rows. 114,726 are desuet
(passé simple `ipsi`, subjonctif imparfait `simp`, conditionnel
1pl/2pl) and dropped by the existing `is_obscure_tag` filter from
`scripts/clue_generation/import_grammalecte_long_words.py`. 13,706 don't
fold to A-Z (rare ligatures, smuggled Greek). The 387,196 admitted tuples
collapse to 321,501 distinct folded A-Z surfaces.

Per-length supply is solid up to L=18 (the `Lexicon.LEXICON_MAX_LEN` cap):
L=15 has 8,953 distinct surfaces, L=18 has 1,314. Beyond that, the corpus
thins — L=19 has one entry — so `Lexicon` is sized appropriately.

## Different meanings of the "same word" — three layers, three mechanisms

The "is `COTE` the same word as `COTE`?" question has three answers. The
existing pipeline already covers the first two; the third is data-only.

### Layer 1: cross-lemma homographs (same surface, different lemmas)

`COTE` ← `côte` (rib/coast, noun, lemma=côte) / `coté` (rated, ppas of
coter, lemma=coter) / `côté` (side, noun, lemma=côté). Three distinct
dictionary entries collapse to one folded surface.

- **Detector**: `scripts/eval/morphology_index.py:MorphologyIndex.by_form`
  returns every `(lemma, tag_set)` analysis for a folded surface.
  `len(set(lemma for ...)) > 1` ⇒ true cross-lemma homograph.
- **Count**: **12,670 folded surfaces** map to ≥2 distinct lemmas in the
  grammalecte universe. (44,218 if you also count purely-inflectional
  collisions like masculine/feminine pairs sharing a surface.)

### Layer 2: cross-POS homographs (same lemma, multiple POS classes)

`change` = noun "money exchange" OR 1sg of verb `changer`. Same surface,
same lemma string, two POS classes.

- **Detector**: `MorphologyIndex.pos_classes_of_form(surface)` returns
  `{nom, verbe, adj, adv}` for the surface.
- **Wired**: `scripts/clue_generation/fix_homographs.py` already detects
  noun+verb homographs, picks the dominant POS by grammalecte frequency,
  and regenerates the clue with an explicit `[nom]` / `[verbe]` hint in
  the LoRA prompt. `apply_homograph_fix.py` writes the chosen variant
  back. Stated coverage: ~12.5% of shipped lemmas.

### Layer 3: same lemma, same POS, multiple senses

`PAVÉ` = paving stone (noun) / hefty book (noun) / sports-field section
(noun) / dense text block (noun). Same lemma `pavé`, same POS (noun),
four DBnary senses.

- **Data**: `data/dbnary/dbnary_fr.csv` carries pipe-delimited multi-sense
  definitions per `(lemma, pos)`. **37,852 entries** have ≥2 senses today.
- **Runtime gap**: the clue picker selects one `WordClue` at placement
  time via uniform random (`SkeletonFiller.pickClue`). It's sense-blind.
  Cycling across days happens via the per-`(word, clue)` cooldown table,
  but if both senses of `PAVÉ` ship as clues, the choice today is random
  rather than context-driven.
- **For grid generation this means**: layer-3 is data we have but the
  runtime doesn't use, so we can promote it to the in-grid dedup key
  without any clue-pipeline work. The experiment below does exactly that.

A genuine sense-context discriminator (theme-aware sense selection at
runtime) is out of scope for this writeup — it would need a (lemma,
sense, context) classifier, not just a new dedup key.

## The experiment

Two pre-processing scripts and one disposable JUnit harness; nothing
checked in.

1. **`/tmp/grammalecte_sense_dump.py`** — reads the grammalecte lexique,
   drops desuet and non-placeable, derives a POS class from the tag set
   (mirrors `fix_homographs.lemma_pos_freq`), joins against DBnary to get
   the sense count for `(lemma, pos)`, emits one row per
   `(folded, lemma, pos_class, sense_id, sense_count, frequency)` tuple.
   330,267 base (folded, lemma) tuples expand to 779,909 sense rows.
   Distribution of sense counts:
   ```
     1 sense  → 175,996 tuples
     2 senses → 62,311
     3 senses → 35,125
     4 senses → 20,180
     5 senses → 12,242
     6 senses → 6,702
     ≥10      → 7,294
   ```
2. **`MaxGridExploration` JUnit test** (env-gated, never run in CI). Loads
   the dump, builds a `WordRepository` with one `Word` per
   `(folded, lemma#sense_tag)` (the encoded `lemma` field carries the
   sense as a trailing `Z` + base-26 alpha tag so existing lemma-keyed
   dedup paths work unchanged), then escalates grid size.
3. Domain knobs (`GenerationKnobs`, `WordAcceptor`, `Grid.fromPlacements`)
   are mutated in place for the run and reverted at the end. None of this
   ships.

## Findings

### The dedup contract dominates everything

Three contracts were measured, all with the grammalecte-321k corpus and
`blackRatio = 0.26`:

| Contract | 60×48 wall | 80×64 wall | 100×80 wall | 150×120 wall | Plausibility |
|---|---:|---:|---:|---:|---|
| **A.** Surface-only dedup + L≤3 unlimited reuse | 0.24 s | 0.68 s | 0.81 s | 92 s (after 1 retry) | Allows the same surface to repeat 200+ times (`AS`×202 at 150×120). Not playable. |
| **B.** Lemma-only dedup (any length, no length carve-out) | 12.7 s | fail (2 min) | fail | fail | Strictly tighter than the prior surface rule (placing `CHAT` blocks `CHATS`); the search runs out of restarts before finding a layout that respects it. |
| **C.** Per-`(lemma, sense)` 5-cap at L≤3 + 1-cap at L>3, with **separate** trackers per length bucket | 0.45 s | 3.7 s | walls at 2 min | — | The principled rule. Max surface dup observed: `AS`×27 at 60×48 (~5 senses of `as`-noun × 5 placements per sense) and `ARMES`×4 at 80×64 (four `(lemma, sense)` variants of L=5 `armes`). |

Contract A produces structurally valid grids but isn't a real mots-fléchés
grid — it'd visibly read as "the same connector token over and over."
Contract B is principled but the CSP can't satisfy it past 80×64 within 2
min: the `BlackCellLayout`'s topology has too many length-2/length-3 cells
and the lemma pool at those lengths is small. Contract C is the user-
visible answer the project should reason about.

### The bug that almost shipped as a result

A first pass at Contract C used a single shared `lemmaCount` map across
both length buckets — placements at L≤3 incremented the same counter the
L>3 check consulted. Result: placing `ES` (L=2, lemma=être) immediately
exhausted the L>3 limit-1 budget for `être`, so `ÊTRE` / `ÉTAIT` / any
length-4-plus inflection of the same lemma got rejected. The CSP found
solutions anyway because it was forced to use rarer lemmas at L>3 — and
the heavy pruning made convergence *faster*. The buggy "150×120 in 92 s"
result was real but its dedup contract was meaningless. The split-tracker
fix (`usedLemmasLong : HashSet` for L>3, `lemmaCountShort : HashMap` for
L≤3) drops the false ceiling to 80×64 under the correct rule.

Lesson: when a knob change makes a hard problem easier, check whether the
new rule is actually the rule you described. A win that comes from
unintended pruning is a measurement artifact.

### Knob tuning notes

- **`DEFAULT_BLACK_RATIO`** moved from the existing 0.14 (production) to
  0.26 (spec §13's authentic-print band). 0.14 worked for the 15×12
  daily-puzzle target but starves the CSP at 50×40+ because more long
  slots → more long-distance crossings → wider constraint graph. 0.26 cut
  60×48 from 1.1 s → 0.45 s with Contract C; 0.30 was worse — the
  no-3-in-a-row rule starts rejecting placements and the layout becomes
  pathological.
- **`BASE_BUDGET_BACKTRACKS`** moved from 200 to 1,000. With the bigger
  Lexicon (multi-sense rows), each restart's setup cost rises, so cheap
  restarts amortize worse. 1,000 was the inflection: 200 wasted attempts
  on dead-end layouts, 5,000 over-explored each attempt and starved the
  Luby restart schedule of fresh seeds.
- **`MAX_RESTARTS`** from 2,000 to 50,000. With the wall-clock budget
  doing the binding, this is just a safety cap. 2,000 hit it cheaply at
  large grid sizes; 50,000 made it irrelevant.
- **`perAttemptSeconds`** got two new tiers: `area ≤ 5000 → 30 s`,
  `else → 120 s`. The existing piecewise topped out at 15 s, which was a
  hard wall once an attempt for a 100×80 layout started making progress.

These knobs are right for the experiment; they are *not* a recommendation
for the production generator. The production target (15×12, ~65 slots,
sub-second p50) is well-served by the current `0.14` ratio + 200 base
budget. Bumping black-ratio for production would change the visual
character of the daily grid and degrade small-grid latency.

### Repetition stats (Contract C, cap=3, blackRatio=0.26, BASE=1000)

| Size | Slots | L≤3 % in repeats | max L≤3 dup | L>3 % in repeats | max L>3 dup |
|---|---:|---:|---|---:|---|
| 60×48 | 1,163 | 73 % | `AS`×27, `ES`×24 | 5 % | `TINE`×2 |
| 80×64 | 2,022 | 77 % | `AS`×21, `ES`×20 | 10 % | `ARMES`×4 |

L>3 reuse is well-behaved (4-max, principled: four distinct
`(lemma, sense)` entries for `armes`). L≤3 reuse looks high in absolute
terms but distributes across distinct senses: `AS`×27 is approximately
five senses of `as` (noun) × five placements per sense, plus a few
placements of `AS` as 2sg of `avoir` (lemma differs). The user-visible
short-word recurrence is real but bounded by the per-`(lemma, sense)`
cap; without that cap, `AS` saturates at 200+ placements on a 150×120
grid (Contract A's failure mode).

### Clue-corpus shortfall

The grammalecte universe lets the generator *fill* a 150×120 grid; the
runtime clue table can *show* only the 25k surfaces with a written clue.
At 80×64 with Contract C, 64% of placed surfaces (≈1,290 of 2,022) lack a
clue in `words-fr.csv`. At 60×48, 60%. The number scales linearly with
grid area, as you'd expect.

This is a *surface-level* shortfall — it doesn't tell you whether the
specific sense placed has a clue, only whether *any* clue for that
surface exists. A sense-true puzzle (one clue per placed `(lemma, sense)`
pair) would carry a strictly larger debt.

## Recipe — how to repeat this

The whole experiment fits in three files, all disposable. Total wall to
get from cold to first result: under five minutes.

1. **Dump grammalecte** (~5 s).
   - Script:
     ```python
     # /tmp/grammalecte_sense_dump.py
     # reads ~/Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt
     # filters: !desuet (ipsi/simp/cond+1pl|2pl), A-Z foldable
     # joins: data/dbnary/dbnary_fr.csv on (lemma_lower, pos_en)
     # emits: /tmp/grammalecte_senses.csv with one row per
     #        (folded, lemma, pos_class, sense_id, sense_count, frequency)
     ```
   - POS-class extraction mirrors
     `scripts/clue_generation/fix_homographs.py:lemma_pos_freq`:
     iterate grammalecte tags, return `nom` on first match (priority),
     `verbe` if any tag starts with `v[0-3]`, else `adj` / `adv`.
   - POS mapping to DBnary: `nom → noun`, `verbe → verb`, `adj → adj`,
     `adv → adv`. Anything else: sense_count = 1.
2. **Loader** — read the dump, encode `sense_id` into the lemma string
   so the existing lemma-keyed dedup paths in `WordAcceptor` and
   `Grid.fromPlacements` work without further changes. Trailing `Z` is
   the separator: `id=0 → "Z"`, `id=1 → "ZA"`, `id=2 → "ZB"`, …, `id=26
   → "ZAA"`. The encoded lemma stays A-Z, which `Word`'s init contract
   requires.
3. **Dedup contract** — patch `WordAcceptor.accepts/recordPlacement/
   removePlacement` with split per-length-bucket trackers:
   ```kotlin
   private val usedLemmasLong = HashSet<String>()      // L>3
   private val lemmaCountShort = HashMap<String, Int>() // L≤3, cap 5

   fun accepts(word: Word): Boolean {
       if (word.text.length > 3) {
           if (word.lemma in usedLemmasLong) return false
       } else {
           if ((lemmaCountShort[word.lemma] ?: 0) >= 5) return false
       }
       return hasUsableClue(word)
   }
   ```
   Mirror the rule in `Grid.fromPlacements.requireNoDuplicateWords` so
   the factory doesn't reject CSP-valid output.
4. **Knobs** — `GenerationKnobs`: `DEFAULT_BLACK_RATIO = 0.26`,
   `BASE_BUDGET_BACKTRACKS = 1_000`, `MAX_RESTARTS = 50_000`,
   `perAttemptSeconds` extended with `area ≤ 5000 → 30`, `else → 120`.
5. **Test heap** — `grid/infrastructure/build.gradle.kts:tasks.test`
   needs `maxHeapSize = "4g"` to fit the ~800k-entry Lexicon comfortably.
6. **Run** — `BLISS_EXPLORATION=true BLISS_SENSE_CAP=3 ./gradlew
   :grid:infrastructure:test --tests "...MaxGridExploration" --rerun-tasks`.
   `BLISS_SENSE_CAP=1` disables DBnary expansion (only cross-lemma
   homographs); `cap=3` applies the first three DBnary senses; `cap=5+`
   is slower and didn't help.

The exploration test logs per-call timing, the rendered grid, and
`L≤3` / `L>3` repetition stats so the dedup contract is visible in the
output. Stash the output anywhere — none of this is meant to land.

## What this exploration is not

- **Not a proposed product change.** The production daily-puzzle target
  is 15×12. The numbers here describe what the generator *could* do
  under aggressive knobs, not what it *should* do.
- **Not a sense-aware clue picker.** The runtime clue picker remains
  sense-blind even with Contract C; this only changes the in-grid dedup
  key. A real sense-aware picker (e.g. theme-conditioned sense selection)
  is a separate piece of work and would need a `(sense, context) → score`
  model the project doesn't have today.
- **Not a corpus expansion plan.** Promoting unclued grammalecte
  surfaces into the runtime corpus would require writing clues for ~88k
  surfaces today missing them — out of scope here, but the size of the
  shortfall is a useful planning number for any future sense-rotation
  feature.

## Related

- ADR-0039 — bitmask-CSP grid generator with Luby restarts. Knob choices
  in this experiment override the defaults from §13 of that ADR;
  rationale for the production defaults sits there.
- ADR-0023 — DBnary CC BY-SA constraints. Multi-sense definitions are
  legitimately under CC BY-SA; using them as a structural signal for
  in-grid dedup (no text rendered) does not change the licence posture
  for `words-fr.csv`. A sense-aware *clue picker* that surfaces DBnary
  text would.
- `scripts/clue_generation/import_grammalecte_long_words.py` — the
  existing lemma-anchored admission gate that produces today's
  `words-fr.csv`. The sense-dump in this experiment is the "drop the
  lemma-anchor gate, keep the desuet filter" variant.
- `scripts/clue_generation/fix_homographs.py` — POS-discriminated clue
  regeneration. Layer 2 of the sense story.
