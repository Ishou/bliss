# ADR-0062: Lemma-anchored clue generation; conjugated forms are grid-time inflections

## Status
Accepted

## Context
The survey POS taxonomy (`Pos`, ADR-0056) carried a `verbe_conjugue`
value, and the clue-generation pipeline (ADR-0013, ADR-0057) accepted
it as a generated/ratable class. This conflated two different things:

- A **clue** is authored against a **lemma** — the dictionary
  headword a rater judges (`AVOIR`, `ÉCLORE`). Generation produces
  lemma clues; the survey rates lemma clues.
- A **grid answer** is the surface string that fills the cells
  (`AVAIT`, `ÉCLOT`). For verbs this is an *inflection* of a lemma,
  chosen at grid-fill time to fit length/interlock constraints. Its
  provenance is `(surface=AVAIT, lemma=AVOIR)`.

Treating `verbe_conjugue` as a first-class generated POS meant the
model was asked to author clues for conjugated surfaces, and the
survey offered raters a class that has no stable lemma to rate. In
practice only a handful of such rows ever existed (five conjugated
survey_items, plus retired `AIMAIT` rows), and they had no coherent
rating target: a clue for `ÉCLOT` is really a clue for `ÉCLORE`.

## Decision
**Clues are generated and rated at the lemma (infinitive) level only.**
Conjugated verb surfaces are produced at grid-fill time by inflecting
a lemma clue; they are never a generated or rated POS.

Concretely, `verbe_conjugue` is removed from the taxonomy and every
path that references it:

- **Survey contract + UI**: dropped from the `Pos` enum
  (`survey/domain/.../Pos.kt`), the `Pos` schema in
  `survey/api/openapi.yaml` (and its `x-enum-varnames`), the
  regenerated `frontend/src/infrastructure/api/survey/types.ts`, the
  hand-maintained `frontend/src/application/survey/types.ts`, and the
  `/contribuer` POS labels (`frontend/src/ui/components/sondage/labels.ts`).
- **Generation**: dropped from the `pipeline_v2` POS allowlist
  (`scripts/clue_generation/pipeline_v2/run_pipeline.py`) and the
  Command-R POS phrasing map (`modal_jobs/04_generate_command_r.py`).

The verb classes that remain are `verbe_infinitif` (the lemma) plus
`participe_passe` / `participe_present` (deverbal forms that function
as their own lemmas for clue purposes).

The five conjugated `survey_items` rows were cleaned up before this
ADR: each was deleted along with its ratings, and the two that named a
real word were re-inserted as lemma rows (`ÉCLORE` — "Sortir du
bouton"; `PINCER` — "Saisir comme un crabe") at gold training weight.
With no stored row referencing the value, the enum removal is a clean
contract change rather than an expand-and-contract migration.

## Consequences

Easier:
- One rating target per clue: a rater always judges a lemma, never an
  arbitrary conjugated surface with no dictionary headword.
- The model's job is narrower and better-posed — author for a lemma,
  let the grid filler inflect.
- The taxonomy matches the data: verbs are `verbe_infinitif`;
  conjugation is a grid-time concern, not a label.

Harder / watch-outs:
- The grid filler owns lemma→surface inflection and the
  `(surface, lemma)` provenance link; that machinery
  (`scripts/eval/inflect_clue.py`, grammalecte morphology) is now the
  sole place conjugation lives. A regression there no longer has a
  `verbe_conjugue` survey row as a backstop.
- `verbe_conjugue` is a hard removal: any future stored value would
  fail `Pos.valueOf`. The cleanup above is what makes that safe.

## Alternatives considered
- **Deprecate-in-place** (keep the enum value, hide it from the UI):
  leaves a dead class in the contract and a perpetual "why is this
  here?" for raters and model prompts. Rejected — only a handful of
  rows existed, so a clean delete is cheaper than carrying the value.
- **A dedicated `OBSOLETE` POS** for retired/ambiguous rows: invents
  taxonomy to model a state the `retired_at` column already captures.
  Rejected as over-engineering.
