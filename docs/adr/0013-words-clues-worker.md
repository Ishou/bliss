# ADR-0013: Words/clues worker — Hunspell-fr ingest + LLM clue generation

## Status

Accepted.

## Context

The grid-api currently ships its French word list as
`grid/api/src/main/resources/fr.json`, a hand-curated 122-entry file
with inline clues. That was the right shape for the
hello-world deployment (ADR-0004) and the first puzzle endpoint
(PR #76), but it does not scale: 122 entries cannot fuel daily
puzzles without immediate repetition, the clues are written by
whoever last touched the file, and the licensing provenance of each
entry is undocumented. A real corpus, with attribution, is overdue.

The first instinct — fetch words from a dictionary at request time
and call the LLM for clues on the hot path — is wrong on two axes.
First, latency: a clue round-trip to Claude is hundreds of
milliseconds, multiplied by every word in a grid, on every puzzle
load. Second, cost and reproducibility: the same word would be
re-clued on every cold cache, and identical puzzles would surface
non-identical clues across requests. The sane shape is an
**offline batch worker** that populates a `words` table with
pre-computed clues and difficulty scores; the API reads from
Postgres and never talks to the LLM directly.

The originally-evaluated word source was **Lexique3**
(`lexique.org`, Boris New & Christophe Pallier). Investigation of
the upstream `chrplr/openlexicon` repository — which redistributes
Lexique alongside its sibling databases — showed the verbatim
license declaration: *"Unless otherwise explained by a individual
readme or license file in a directory, it distributed under a
[CC BY-SA 4.0 LICENSE](https://creativecommons.org/licenses/by-sa/4.0/)."*
**CC BY-SA 4.0 is copyleft**: redistributing derivative works
(the populated `words` table, the clues, anything we publish that
incorporates the corpus) requires us to license those derivatives
under CC BY-SA 4.0 as well. That is incompatible with the rest of
this codebase, which is intended to remain under a permissive
license, and it is incompatible with the LLM-generated clues we
plan to ship as a product surface. We are not going to viral-license
the puzzle corpus.

The pivot is to **Hunspell-fr** as packaged in
`LibreOffice/dictionaries/fr_FR`, whose `README_fr.txt` declares
verbatim: *"MPL : Mozilla Public License version 2.0 --
http://www.mozilla.org/MPL/2.0/"*. MPL 2.0 is file-scoped copyleft:
it covers the dictionary files themselves, not derivative works
built from the word list as data. Ingesting Hunspell-fr to populate
the `words` table is exactly the case MPL 2.0 was designed to
accommodate, and it leaves the API code, the migrations, and the
generated clues unencumbered. The trade-off is a coarser frequency
signal — Hunspell-fr is a spelling dictionary, not a frequency
corpus — which we mitigate via the difficulty heuristic in §4 below
and a coarser proxy when an external frequency source is added
later.

## Decision

### 1. Word source: Hunspell-fr (MPL 2.0)

The corpus is the **`fr_FR` Hunspell dictionary** from
`LibreOffice/dictionaries`, license **MPL 2.0** as quoted in
Context. The expanded surface form list (the output of running
`unmunch` against the `.aff` + `.dic` pair, stripping affix-only
forms) is the input to the importer.

**Attribution surface — three places, all required:**

1. A new **`NOTICE.md` at the repo root** lists the source
   (`LibreOffice/dictionaries fr_FR`), the upstream version pinned
   at import time, the license (`MPL 2.0`), and the canonical URL.
2. The `words.source` and `words.source_license` columns
   (see §3) carry per-row provenance — auditable at the data layer
   without grepping repo files.
3. A future **`Clue.source` field on the API response** surfaces
   attribution to end users. The shape of that field is owned by a
   later ADR; this one only commits to the column existing on the
   data side so the API can read it when the time comes.

### 2. Filter rules

The importer keeps only entries where:

- `length(word) ∈ [3, 9]` — matches the grid sizes the puzzle
  generator targets today.
- `language = 'fr'` — single-language scope; multi-language is
  out of scope (see Notes).
- The surface form is purely alphabetic (Unicode letter category),
  with French diacritics preserved. No hyphens, no apostrophes, no
  digits. Words containing apostrophes or hyphens are dropped at
  ingest, not split.
- The form is lower-cased before insertion. Proper nouns from the
  dictionary's capitalized entries are dropped at ingest.

### 3. Schema

A new `words` table, owned by `grid-api`'s Postgres database (the
CNPG cluster from ADR-0009):

```sql
CREATE TABLE words (
  id              BIGSERIAL PRIMARY KEY,
  word            TEXT NOT NULL,
  language        TEXT NOT NULL DEFAULT 'fr',
  length          INT GENERATED ALWAYS AS (length(word)) STORED,
  difficulty      REAL,
  clue            TEXT,
  source          TEXT NOT NULL,
  source_license  TEXT NOT NULL,
  frequency       REAL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (word, language)
);
CREATE INDEX words_lang_len ON words (language, length) INCLUDE (difficulty);
```

`clue` and `difficulty` are nullable: the `import-lexique`
sub-command populates everything except those two; `generate-clues`
fills them in a second pass. `frequency` is nullable for the same
reason — Hunspell-fr does not ship frequency data, so the column
stays empty until a frequency source is layered on top.

### 4. Difficulty formula

`difficulty = sigmoid(α·log(rank) + β·(length−5))`, normalized to
`[0, 1]`. `rank` is the row's position in a frequency-sorted list
(absent a real frequency signal from Hunspell-fr, the importer
falls back to alphabetical rank as a placeholder, with the
`frequency` column left null and the difficulty re-computed when
a real frequency source lands). `α` and `β` are tuning constants
checked into the worker config; their initial values (`α = 0.15`,
`β = 0.20`) are picked to keep the centre of the distribution
around `0.5` for a 5–6 letter median word and are not load-bearing —
see Notes for the player-data difficulty path that supersedes this.

### 5. Clue generation

Clues are generated by **Claude API** calls from the
`generate-clues` sub-command. Constraints:

- Each clue is **≤18 characters** including spaces. The prompt
  enforces this; the worker re-prompts up to 2× on overrun and
  drops the row if all attempts overrun.
- The model used and the prompt template are pinned in the worker
  source — changing either is a code change with an ADR-class
  audit trail.
- One clue per `(word, language)` row. Re-running `generate-clues`
  on a row with a non-null `clue` is a no-op unless `--force` is
  passed.
- The Claude API key is injected at runtime per the Security
  section of `CLAUDE.md` — never in the worker image, never in the
  Flyway artifact, never in CI logs.

### 6. Migration tooling: Flyway

Schema migrations are **Flyway**, applied by the worker on startup
and by `grid-api` on its own startup against the same database.
The ADR-0009 backward-compatibility rule (expand-and-contract)
applies: the initial migration is purely additive; later changes
to `words` ship as paired expand/contract migrations.

### 7. Worker module shape: `grid/worker/`

A new Kotlin CLI module at **`grid/worker/`**, sibling to
`grid/api/` and `grid/domain/`. It depends on `grid/domain` for
the word entity shape but **does not** depend on `grid/api` —
this is a separate process, a separate deploy artifact, and a
separate dependency graph (per the bounded-context rule in
`CLAUDE.md`). Sub-commands:

- **`import-lexique`** — reads the unmunched Hunspell-fr word
  list from a path argument, applies the §2 filters, computes
  difficulty per §4, inserts into `words` with `source =
  'hunspell-fr'` and `source_license = 'MPL-2.0'`. Idempotent on
  `(word, language)`.
- **`generate-clues`** — selects rows where `clue IS NULL`,
  batches them through the Claude API with the §5 prompt,
  writes the results back. Resumable on interruption.

The sub-command name `import-lexique` is kept as historical
shorthand even though the source is Hunspell-fr; renaming it to
`import-words` is a follow-up the implementation PR may take or
defer.

### 8. Future API switch

The `grid-api` puzzle endpoint reads from a
`DatabaseWordRepository` backed by the `words` table. The
existing `fr.json`-backed repository stays in the codebase as a
fallback for local development and tests until the
`DatabaseWordRepository` is feature-complete; deletion of
`fr.json` is its own follow-up PR (see below).

## Consequences

### Easier

- **Real corpus, real attribution.** Every word in the puzzle is
  traceable to an MPL-2.0 source via the `source` /
  `source_license` columns and `NOTICE.md`. No more 122-entry
  hand-curated list with murky provenance.
- **Hot-path latency drops.** No LLM call on puzzle load; the
  API reads pre-computed clues from an indexed Postgres table.
  RED metrics on `/v1/puzzles` get measurably better.
- **Reproducible clues.** The same word always shows the same
  clue, which makes E2E tests deterministic and customer-support
  bug reports actionable ("the clue for X is wrong" maps to one
  row, not a non-deterministic LLM output).

### Harder

- **A new deploy artifact.** `grid/worker/` is a separate Kotlin
  CLI image, with its own Dockerfile, its own CI pipeline, and
  its own scheduled run (initially manual; a CronJob is a
  follow-up). The k8s manifest for it lands with the seed-run
  PR.
- **Clue-generation cost is real.** A first ingest of Hunspell-fr
  filtered to 3–9 letters is on the order of tens of thousands of
  rows; clue generation against Claude is a one-time spend in the
  low tens of dollars, but it is a non-zero spend that has to be
  accounted for. Re-runs are gated behind `--force`.
- **Frequency signal is coarse.** Hunspell-fr is a spell-checker
  dictionary, not a frequency corpus. The §4 fallback to
  alphabetical rank is honest but crude; difficulty quality
  improves materially only when a frequency source is layered on.

### Different

- **Schema lives in `grid-api`'s database.** Not a separate
  worker database. The worker writes; the API reads. Both apply
  Flyway migrations on startup against the same CNPG cluster.
- **`fr.json` becomes a dev-mode fallback, then dies.** The
  in-repo word list is no longer the production source of truth
  the day the `DatabaseWordRepository` ships; it survives only
  to keep `./gradlew test` and `docker compose up` working
  without a populated database, and is deleted after the seed
  run is verified in production.
- **Attribution is a product surface, not a footnote.**
  `NOTICE.md` at the repo root and the `source_license` column
  make the MPL-2.0 obligation visible to anyone touching the
  code or the data. The future `Clue.source` API field extends
  this to end users.

## Notes

Out of scope:

- **Multi-language support.** `language` is a column from day
  one, but only `'fr'` is populated. An English corpus, a
  Spanish corpus, etc. are each their own ADR with their own
  source/license analysis.
- **Player-data difficulty.** The §4 sigmoid is a cold-start
  heuristic. Once we have player solve-rate data, difficulty
  becomes an empirical function of "how often did people solve
  this word in this grid", and §4 is superseded. That ADR is
  not this ADR.
- **API-level caching of clues.** The API reads from Postgres on
  every request today; an in-process or Redis cache is a
  performance follow-up, not a correctness one.
- **Attribution on the API response.** The `Clue.source` field
  in §1 is a future API contract change. This ADR commits to
  the column existing; it does not commit to the response shape.

### Follow-up implementation

This ADR unblocks five sequenced PRs:

1. **Schema migration PR.** Flyway migration adding the `words`
   table per §3 to the `grid-api` database. No worker code yet;
   API code unchanged.
2. **`import-lexique` sub-command PR.** Adds `grid/worker/`
   with the Hunspell-fr importer, Dockerfile, and CI build.
   Pinned upstream version; checksummed input.
3. **`generate-clues` sub-command PR.** Layers the Claude-backed
   clue generator on top of the worker. Prompt template,
   retry/length-cap logic, `--force` flag.
4. **`DatabaseWordRepository` swap PR.** `grid-api` reads from
   `words` instead of `fr.json` behind a feature flag (per the
   `CLAUDE.md` "deploy dark, release bright" rule). `fr.json`
   stays in-tree as the dev-mode fallback.
5. **Initial seed run + `fr.json` retirement PR.** Worker run
   in production, attribution verified end-to-end, feature flag
   flipped on, `fr.json` deleted. `NOTICE.md` lands no later
   than this PR.

PR boundaries follow the `CLAUDE.md` 400-line rule; the
schema migration and `NOTICE.md` are intentionally cheap to
review on their own.
