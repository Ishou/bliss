# Design — Survey-side accent preservation (clue-AI pipeline)

## Problem

The lemma-curation step (`scripts/clue_generation/build_pos_lemmas.py`)
strips diacritics from every DBnary lemma when emitting the
`(mot, pos)` list that drives generation. The fold uses Unicode NFD +
combining-mark removal as a **dedup key** but then writes the stripped
form to disk as the canonical surface. Result: distinct surfaces that
share a folded form collapse silently. `solde` (noun, "Excédent") and
`soldé` (past-participle adjective, "Soudé") become one row
`SOLDE;adjectif` in the lemma file. The generator's prompt receives
`SOLDE` for an adjective sense whose actual spelling is `SOLDÉ`, so it
either guesses the sense from POS alone or fabricates an adjective
reading for the noun.

Downstream, `survey_items.mot` stores uppercase ASCII (no DB constraint
forces this, but every producer feeds ASCII in). `/sondage` shows the
maintainer `SOLDE adjectif` when the rated word is *actually* `SOLDÉ`
— losing the accent that determines the correct sense and the
correctif's pronunciation. The maintainer can write a correctif but
cannot edit the headline `mot`; the displayed cue is wrong.

Confirmed in prod (2026-06-01): 5,584 `synthetic_v1` + 110
`rater_proposed` + 114 `gold` + 8 `manual` survey items, all stored
uppercase ASCII. `SELECT mot FROM survey_items WHERE mot ~ '[a-zà-ÿ]'`
returns zero rows.

## Goal

Accents are preserved end-to-end from DBnary → lemma curation →
generation → `survey_items` → `/sondage` display. The single place
where folding still happens is at `bliss-worker export-words` time,
when writing `grid/infrastructure/.../words-fr.csv` — the grid-cell
boundary is the only ASCII gate.

A maintainer rating an adjective sense of `solde` sees `SOLDÉ` in
`/sondage`, not `SOLDE`.

## Non-goals

- **Grid wordlist / cell content** stays ASCII. Word domain invariant
  `text.all { it in 'A'..'Z' }` remains. `BitmaskCsp` placement,
  `ValidatePuzzleUseCase` letter compare, and `Cell` rendering are
  untouched. A follow-up spec can revisit if and when /sondage with
  accents reveals a real pull toward accented grids.
- **Multi-sense disambiguation** within the same `(mot, pos)`. Multiple
  DBnary senses of `solde nom_commun` ("excédent", "rémunération
  militaire", …) remain collapsed to one generated clue per item; the
  rater can still propose a correctif. Different accented surfaces
  (`solde` vs `soldé`) are the only "multi-sense" axis this spec
  separates.
- **Adapter retraining cycle.** Round-10's adapter was trained on
  ASCII prompts; serving it accented inputs causes one round of
  slightly weaker generation. Round-11 absorbs the new distribution
  naturally via the next `winners_round_*.csv` and the corpus build.
  No special retraining action required by this spec.

## Approach

Approach B from the brainstorm ("single coherent PR, all changes ship
together"). Considered alternatives:

- **A — Display-only frontend fix:** decorate `/sondage` with a
  derived accented form. Doesn't fix the upstream conflation —
  `solde` noun and `soldé` adjective stay one row in `survey_items`,
  so the rater still can't distinguish two genuine sense-surfaces.
  Rejected.
- **C — Phased (lemma curation in PR1, backfill in PR2):** doubles PR
  overhead for a small workstream; creates a window where new items
  show accents but historical items don't, exactly the "this column
  means different things depending on when the row was written" trap
  to avoid. Rejected.
- **D — Add `mot_accented` alongside ASCII `mot`:** dead-leg compat
  column maintained forever. Rejected.

## Data model

`survey_items.mot` is already `TEXT NOT NULL` with no CHECK constraint
on character class — accented uppercase already passes the schema.
**No Flyway migration is needed.**

Stored value convention: **uppercase with accents preserved**, e.g.
`SOLDÉ`, `ÉCOLE`, `CÉDÉ`. Matches the existing all-caps `/sondage`
headline convention and modern French typography (Académie française
guidance: uppercase accents are part of the orthography).

No new column. The folded form is derivable on the fly via Unicode NFD
strip when a consumer needs it (the grid worker, dedup keys). Adding a
denormalized `mot_folded` column would invite drift; computed-on-read
is cheaper than maintained-and-trusted.

## Producer-side changes

### `scripts/clue_generation/build_pos_lemmas.py`

`_strip()` keeps its role as the dedup *key* in the
`{stripped_lemma: {enum_pos: …}}` accumulator. The emitted CSV value
changes from the stripped form to the **original accented lemma
uppercased**. Implementation: thread the original `row["lemma"]`
through to output keyed by `(stripped, pos)`. When two raw lemmas
share `(stripped, pos)` (rare; the DBnary parser typically gives one
lemma per `(stripped, pos)` pair), pick the most-frequent variant; log
others.

Output goes from `SOLDE;adjectif` to `SOLDÉ;adjectif`. The dedup
contract is unchanged.

### `modal_jobs/04_generate_command_r.py`

**No code change.** The prompt template stays
`Donne une définition de mot fléché pour {mot}. Style : {style}.`
with `{mot}` substituted from the lemma file. Because the lemma file
now carries `SOLDÉ` instead of `SOLDE`, the prompt naturally receives
the accented form.

The current round-10 adapter was trained on ASCII prompts (the
training corpus's `mot` column was ASCII via `survey_items.mot` and
`gold_pilot_v1.csv`). Serving accented inputs is a minor distribution
shift for one round. Round-11 retrains on `winners_round_11.csv`
which carries accented `mot` (from the backfilled survey items), so
the distribution normalizes.

### `bliss-worker ingest-clue-candidates` + `StyleGuideCsvParser.kt`

`StyleGuideCsvParser.kt` currently uppercases the `mot` column. The
new code path lets accented uppercase pass through. Validation regex
becomes `Regex("^[A-ZÀ-Ÿ]+\$")` (the Unicode block `À-Ÿ` covers the
full French uppercase accent set including the rare `Ÿ`). Round-trip
with `StyleGuideCsvWriter.kt` is byte-preserving.

The fold to ASCII happens **only** at `bliss-worker export-words` time
in the existing `foldToAscii` call inside `CsvWordRepository.kt` —
that line stays exactly as-is. `words-fr.csv` is the grid-cell-content
boundary and unchanged by this spec.

## Migration: backfill historical `survey_items`

One-shot script `scripts/survey/backfill_accents.py`, modeled on the
existing `scripts/survey/backfill_campaigns.py`:

1. Load DBnary lemma map from `data/dbnary/dbnary_fr.csv` into a
   `(stripped_lower_lemma, pos) → accented_lemma` lookup. Handles the
   `solde`+`soldé` case by keeping them separate keys via POS.
2. For each `survey_items` row, lookup
   `(lowercase(current_mot), pos) → accented_lemma`. Issue
   `UPDATE survey_items SET mot = upper(accented_lemma) WHERE …`.
3. Items where no lookup match exists (rater-typed compound
   expressions, rare composites, non-headword inflected forms): leave
   `mot` as ASCII, log to stderr for manual review. The 110
   `rater_proposed` items are the most likely candidates here.
4. Idempotent — re-running on a row whose `mot` already matches the
   lookup output is a no-op.

Sequencing: backfill runs **after** the producer-side PR merges, so
new items inserted between PR merge and backfill execution are also
already accented.

### Edge cases

- **Lookup ambiguity** (multiple accented variants fold to the same
  `(stripped, pos)`): rare in practice. The DBnary CSV doesn't carry a
  frequency field, so tie-break alphabetically (first by Unicode order)
  for determinism and log all collapsed variants for visibility.
- **`rater_proposed` items with no DBnary match**: log + skip. The
  maintainer can manually fix via a follow-up correctif once /sondage
  starts showing the accented form.
- **`gold` and `manual` items**: 122 total, small enough to eyeball
  the backfill log and confirm correctness. A handful (`ÉCOLE`,
  `ÉTÉ`, …) need backfilling; the rest are accent-free common nouns
  and pass through unchanged (`PAIN → PAIN`).

## Testing

- **`build_pos_lemmas.py`**: extend tests with a fixture row
  `lemma="soldé"`, assert the emitted CSV preserves the accent
  (`SOLDÉ;adjectif`). Existing dedup + désuet-filter tests keep
  passing.
- **`backfill_accents.py`**: testcontainer Postgres, seed three rows:
  one straightforward (`SOLDE;adjectif → SOLDÉ`), one non-recoverable
  (`XYZQZW` not in DBnary → stays `XYZQZW`), one already-accented
  (`SOLDÉ` re-run → no-op). Idempotency assertion on rerun.
- **`StyleGuideCsvParser`**: extend `StyleGuideCsvRoundTripTest.kt`
  with an accented `mot` row; confirm parse → write → parse is
  byte-preserving.
- **`/sondage` display**: visual check in dev cluster after backfill.
  Open `/sondage`, confirm `SOLDÉ` (or another accented item) renders
  correctly.
- **End-to-end smoke**: local
  `python3 -m scripts.clue_generation.build_pos_lemmas …`, eyeball
  that accented surfaces survive in the output CSV.

No new Konsist arch tests; no new e2e. Data-shape work, not
architecture.

## Rollout

1. Land single PR: `build_pos_lemmas.py` change, `StyleGuideCsvParser`
   tolerance, `backfill_accents.py` script + tests, sample-fixture
   updates if any. Spotless / commitlint / DCO / Konsist / OpenAPI
   drift all stay green (no schema or API change).
2. Run `python3 scripts/survey/backfill_accents.py --dsn $SURVEY_DB_URL`
   against prod survey-api Postgres. Inspect the stderr log for the
   non-recoverable list; eyeball-confirm there's nothing surprising.
3. Open `/sondage`, confirm an adjective-of-`solde`-style item renders
   accented. Pull a random sample of 10 items via psql to spot-check
   coverage.
4. Round-11 training pipeline naturally picks up accented `mot` in
   `winners_round_11.csv` and the generator prompt. No round-specific
   code change.

Total surface: one PR + one one-shot script run. No schema migration,
no UI change, no adapter retrain scheduling change.

## ADR coverage

- **ADR-0058 (`data/dbnary/**`, DBnary SA-acceptance + distribution
  discipline):** the backfill script reads `data/dbnary/dbnary_fr.csv`
  as a structural lookup table — mapping a `(stripped_lemma, pos)`
  key to its original accented surface. This is the same "filter-only
  use" pattern already followed by `build_pos_lemmas.py` (which reads
  DBnary glosses solely to drop désuet senses). No DBnary text is
  emitted to a deployed artefact, and the accented lemma string is
  the form already present in `survey_items.mot` for new items
  generated post-PR — the migration just brings historical rows into
  alignment.
- **ADR-0001 §4 (400-line cap):** the implementation is expected to
  land in a single PR; estimated diff is ≈250–350 lines (one Python
  script ~120 lines, one Kotlin parser tweak ~20 lines, one
  `build_pos_lemmas.py` edit ~40 lines, plus tests). No override
  needed.

## Open questions

None at design time. All decisions resolved in the brainstorm
transcript:

- Mot casing: uppercase-with-accents (matches existing convention).
- Scope: survey side only; grid stays ASCII per separate decision.
- Migration strategy: single PR with backfill script (not phased).
- DB schema: no Flyway migration needed (no existing CHECK).
- Adapter retraining: implicit at next round (no special action).
