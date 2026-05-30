# Design: Target-style distribution for clue generation

Date: 2026-05-30
Scope: `modal_jobs/04_generate.py` (clue generation). Generation-only.

## Context

The `/sondage` survey serves clues for manual rating; the maintainer's
ratings and fixes feed the clue-generation training base. The live pool is
roughly uniform across five styles (each ~20%) because generation
(`modal_jobs/04_generate.py`) produces a **cartesian product** — every lemma
× a hardcoded list of five active styles, `n_per_pair` clues each:

```python
STYLES_ACTIFS = ["definition_directe", "periphrase", "culturel", "cryptique", "fonction_role"]
pairs = [(m, s) for m in lemmes for s in STYLES_ACTIFS]
```

Two styles the maintainer wants are effectively absent from the corpus
(`metonymie` = 3 items, `technique` = 1 item) because they are not in
`STYLES_ACTIFS` at all. The maintainer wants generation to follow a **target
style distribution** so the corpus — and therefore the training base — grows
toward that target. Low-quality output for the new styles is acceptable: the
maintainer rates it and the model retrains (human-in-the-loop flywheel).

The decision to drive **generation** (not survey serving) was explicit: the
survey sampler is style-blind and stays that way; growing the rare styles
must start at the source, because you cannot serve items that do not exist.

## Goal

Replace the uniform cartesian product with **deterministic per-style
allocation** driven by a committed target distribution, so a generation run
produces clues in the target style mix and begins producing `metonymie` /
`technique` at all. The target holds on the **post-filter accepted** clues
(what gets rated and ingested), not on what is requested — generation
**over-produces** to compensate for uneven `pipeline_v2` rejection.

## Target distribution

| style                   | weight |
|-------------------------|--------|
| definition_directe      | 0.217  |
| periphrase              | 0.217  |
| cryptique               | 0.216  |
| culturel                | 0.10   |
| fonction_role           | 0.10   |
| metonymie               | 0.10   |
| technique               | 0.05   |
| calembour               | 0 (hors-IA, `clue-style-guide-v2.md` §4.5) |
| cryptique_morphologique | 0 (not generated) |

Sums to 1.0. Omitted styles are 0.

## Approach (chosen: A — deterministic allocation + committed YAML + adaptive top-up)

Considered:
- **A. Deterministic per-style allocation, targeting accepted counts.** The
  target is expressed as **accepted** (post-filter) clues per style:
  `target_s = round(n_target · wₛ)`. Generation over-produces and tops up per
  style until each reaches `target_s` or a cap. Exact control over absolute
  accepted counts — critical for the rare styles being grown.
- **B. Probabilistic per-lemma sampling.** Draw a style per lemma. Smaller
  diff but distribution only holds in expectation, on *requested* counts, with
  no filter compensation. Rejected.

Weights live in a **committed YAML** (reviewable, reproducible, canonical
target) rather than a CLI-only flag.

### Over-generation: adaptive top-up loop

Per-style filter pass-rates are unknown a priori (especially for the new
`metonymie` / `technique`), so a static inflation multiplier would be a guess.
Instead the GPU run loops:

1. Compute `target_s = round(n_target · wₛ)` for each style.
2. Pass `p` (from 0): for every style with `accepted_s < target_s`, request
   `ceil((target_s − accepted_s) · inflation)` pairs (default `inflation = 2.0`),
   assigning lemmas by striding with a pass-dependent offset (reusing lemmas
   with fresh samples when shortfall exceeds the lemma count).
3. Generate + filter that batch; update `accepted_s`.
4. Stop when no style is short, or after `max_passes` (default 5).

This makes the **accepted** distribution match the target. A style with a
near-zero pass-rate (model can't produce it) stops at `max_passes` and is
reported as a shortfall — bounded GPU spend, transparent outcome. The loop
lives **inside `generate_remote`** so the model stays loaded across passes;
the filter (`traiter_ligne`) is already in-process there.

## Components

### 1. `modal_jobs/style_distribution.yaml` (new, committed)

```yaml
styles:
  definition_directe: 0.217
  periphrase:         0.217
  cryptique:          0.216
  culturel:           0.10
  fonction_role:      0.10
  metonymie:          0.10
  technique:          0.05
```

### 2. Pure allocation helpers (GPU-free, unit-testable)

Two pure module-level functions in `04_generate.py`, importable both locally
(tests) and inside the remote:

- `cibles_acceptation(weights, n_target) -> dict[str, int]` — target accepted
  count per style via **largest-remainder (Hamilton) allocation**, so the
  per-style targets sum *exactly* to `n_target` (e.g. `n_target = 100` →
  `ddirecte 22 / periphrase 22 / cryptique 21 / culturel 10 / fonction_role 10
  / metonymie 10 / technique 5`). Naive `round()` would sum to 101.
- `paires_pour_manque(cibles, acceptes, lemmes, seed, pass_idx, inflation) ->
  list[(mot, style)]` — given targets and accepted-so-far, allocate the next
  batch of pairs for styles still short, inflated by `inflation`, assigning
  lemmas by **striding** with a `pass_idx`-dependent offset (reusing lemmas
  with fresh samples when shortfall exceeds the lemma count). Returns `[]` when
  no style is short. Deterministic given `seed`.

The decision logic — how many pairs to request next, for which styles — is
therefore unit-testable without a GPU. The remote only orchestrates passes.

### 3. Refactor `generate_remote` into a top-up loop

Receive `cibles: dict[str, int]`, `lemmes`, `inflation`, `max_passes`, `seed`
(instead of building the cartesian inline). Load the model once, then loop:
`paires_pour_manque(...)` → generate + filter → update `accepted_by_style`,
until the helper returns `[]` or `max_passes` is reached. The model stays
loaded across passes.

**Generation must use `do_sample=True`** (with temperature, e.g. 0.8) for
every pass — the current `do_sample=(n_per_pair > 1)` is greedy by default, so
re-requesting a reused `(mot, style)` pair in a top-up pass would regenerate an
identical clue and never close the shortfall. The old `n_per_pair` parameter is
removed; the top-up loop subsumes "multiple samples per pair."

### 4. Local entrypoint `generate`

New parameters:
- `style_config: str = "modal_jobs/style_distribution.yaml"`
- `n_target: int | None = None` → defaults to `len(lemmes)` (target *accepted*
  clues).
- `inflation: float = 2.0`, `max_passes: int = 5`.

Flow: load + validate YAML → `cibles_acceptation(...)` → **print the per-style
target plan** → launch the Modal run immediately (no confirmation prompt).

### Default run size

`n_target` defaults to `len(lemmes)` — one *accepted* clue per word on average.
A **100-word batch → ~100 accepted clues**, distributed:

```
definition_directe 22, periphrase 22, cryptique 21,
culturel 10, fonction_role 10, metonymie 10, technique 5
```

This keeps a single batch small enough to rate without fatigue (the binding
constraint). The loop may *generate* well more than 100 to land ~100 accepted.
Denser runs: pass a larger lemma file or an explicit `--n-target`. The
distribution scales with batch size.

## Validation (fail fast, locally, before any GPU spend)

- Weights sum to 1.0 ± 0.001.
- Every key is one of the 9 valid styles.
- `calembour` weight must be 0 (hors-IA).

## Output

Extend `summary.json` (and the local recap print) with:
- `target_by_style` — target accepted count per style.
- `accepted_by_style` — clues per style that survived `pipeline_v2`.
- `requested_by_style` — pairs actually generated per style (shows the
  over-generation: requested ≫ accepted for low-pass-rate styles).
- `shortfall_by_style` — `target − accepted` for any style that did not reach
  target within `max_passes` (e.g. `technique` if the model can't produce it).
- `passes` — number of top-up passes run.

`shortfall_by_style` plus `requested_by_style` are the feedback signal: they
show which styles the model cannot yet produce at quality and how much GPU the
over-generation cost. The maintainer adjusts weights, `n_target`, or
`max_passes` next run, and the rate-and-retrain flywheel improves the
low-pass-rate styles over time.

## Testing (TDD)

`cibles_acceptation`:
- per-style targets match weights within ±1 (rounding),
- targets sum to `n_target`,
- only configured (>0) styles appear.

`paires_pour_manque`:
- requests only for styles below target,
- request count = `ceil((target − accepted) · inflation)` per short style,
- returns `[]` when all targets met (loop-termination contract),
- striding offset varies with `pass_idx` (successive passes draw different
  lemmas),
- deterministic under a fixed seed.

YAML validation: bad sum, unknown style, and `calembour > 0` are each
rejected. No model required for any test.

## Out of scope (separate follow-ups)

- **Survey sampler** — stays style-blind; not touched.
- **Gold-source tagging** for the maintainer's `correctif` fixes
  (`RATER_PROPOSED` → `GOLD`); a survey-API change, deferred.
- **Source-weighted training export** — `source=GOLD` carries no automatic
  training weight today; wiring that is deferred.
- **Retraining** — out of scope; the maintainer runs that separately.

## Size

One file edited (`04_generate.py`) + one new config + one new test file.
Well under the 400-line cap; single workstream.
