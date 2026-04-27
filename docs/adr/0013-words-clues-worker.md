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

- `language = 'fr'` — single-language scope; multi-language is
  out of scope (see Notes).
- The surface form is purely alphabetic (Unicode letter category),
  with French diacritics preserved. No hyphens, no apostrophes, no
  digits. Words containing apostrophes or hyphens are dropped at
  ingest, not split.
- The form is lower-cased before insertion. Proper nouns from the
  dictionary's capitalized entries are dropped at ingest.

**No length filter at import.** The importer ingests every length
the dictionary ships. The grid generator and `DatabaseWordRepository`
restrict their queries to `length ∈ [2, 9]` — that is the range the
puzzle generator targets today. Pulling outside that range later
(longer words for themed puzzles, 2-letter glue words for tighter
grids) is a query-side change, not a re-import. The `words_lang_len`
index in §3 keeps the bounded query cheap regardless of how much
long-tail data sits in the table.

### 3. Schema

A new `words` table, owned by `grid-api`'s Postgres database (the
CNPG cluster from ADR-0009):

```sql
CREATE TABLE words (
  id              BIGSERIAL PRIMARY KEY,          -- physical PK, internal only
  word_id         UUID        NOT NULL DEFAULT gen_random_uuid(),
  word            TEXT        NOT NULL,
  language        TEXT        NOT NULL DEFAULT 'fr',
  length          INT GENERATED ALWAYS AS (length(word)) STORED,
  difficulty      REAL,
  clue            TEXT,
  source          TEXT        NOT NULL,
  source_license  TEXT        NOT NULL,
  frequency       REAL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (word, language),
  UNIQUE (word_id)
);
CREATE INDEX words_lang_len ON words (language, length) INCLUDE (difficulty);
```

`word_id` is the identifier to be surfaced on the wire (per
ADR-0003 §6, which forbids integer IDs on the API). `id`
(BIGSERIAL) remains internal — never returned by the API, never
referenced in external contracts. Postgres 16 does not ship UUID v7
generation natively; the schema-migration PR will resolve the exact
mechanism (generate UUID v7 in the worker before insert, or adopt a
utility extension). `gen_random_uuid()` (UUID v4) is the default
until then.

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
- **`generate-clues`** — selects rows where `clue IS NULL AND
  length BETWEEN 2 AND 9` (the in-app query window from §2),
  batches them through the Claude API with the §5 prompt, writes
  the results back. Resumable on interruption. `--all-lengths`
  drops the length predicate when we want to clue the long tail.
- **`export-words`** — selects all rows where `clue IS NOT NULL`
  for the configured language (default `fr`), writes them to a
  CSV at `--output <path>` (default
  `grid/api/src/main/resources/words/words-<lang>.csv`). Sorted by
  `(language, word)` for stable git diffs. Idempotent — re-running
  on the same DB state produces a byte-identical file. The CSV is
  the production source of truth for `grid-api` (see §8).

### 8. API source of truth: CSV in repo

**Amended.** The original §8 had `grid-api` reading from a
`DatabaseWordRepository` against the in-cluster Postgres. That
path was rejected in favour of a **CSV committed to the repo**
for three reasons made explicit during implementation: fine
control over clue generation (review-then-commit, not autonomous
in production); auditability (the dataset diffs in git, deploys
with the image, and is reproducible across environments); and
zero remote autonomous LLM spend or rate-limit risk on the
running services.

The production grid-api reads from
`grid/api/src/main/resources/words/words-<lang>.csv` via a
classpath-backed `CsvWordRepository`. The CSV's columns mirror
the persisted columns of the `words` table that are relevant to
the API (no DB-internal `id` / `word_id` / `created_at`):
`word, language, length, difficulty, clue, source, source_license`.
Header row required. Sorted by `(language, word)` for stable git
diffs. Rows without a clue are excluded from the CSV — the API
requires one clue per word, and `export-words` (§7) guarantees
the invariant on emit.

The `words` table stays as a **local-dev scratch space** for the
worker pipeline: `import-words` → `generate-clues` → operator
review → `export-words` → commit the CSV. The pipeline itself is
unchanged from §2 / §5 / §7; only the production read path moves
out of Postgres and into the in-tree CSV. The Flyway migration
(§6) stays — it's how the local-dev DB gets created in the first
place.

`fr.json` is removed in the same PR that introduces the CSV.
There is no `WORDS_SOURCE` flag and no fallback path: keeping two
sources to drift apart is exactly the failure mode the CSV is
designed to prevent.

## Consequences

### Easier

- **Real corpus, real attribution.** Every word in the puzzle is
  traceable to an MPL-2.0 source via the `source` /
  `source_license` columns and `NOTICE.md`. No more 122-entry
  hand-curated list with murky provenance.
- **Hot-path latency drops.** No LLM call on puzzle load; the
  API reads pre-computed clues from the committed in-image CSV.
  RED metrics on `/v1/puzzles` get measurably better.
- **Reproducible clues.** The same word always shows the same
  clue, which makes E2E tests deterministic and customer-support
  bug reports actionable ("the clue for X is wrong" maps to one
  row, not a non-deterministic LLM output).

### Harder

- **No new deploy artifact.** `grid/worker/` is a local-dev tool,
  not a deployed service. There is no Dockerfile, no CronJob, no
  k8s manifest. The only artifact that ships is the CSV committed
  to the repo alongside `grid-api`.
- **Clue-generation cost is real.** A full ingest of Hunspell-fr
  is on the order of hundreds of thousands of surface forms; clue
  generation against Claude is gated to the 2–9 query window via a
  `WHERE length BETWEEN 2 AND 9` filter on the `generate-clues`
  selector, keeping the first-pass spend in the low tens of dollars.
  Generating clues for the long tail outside that window is opt-in
  via `--all-lengths`. Re-runs are gated behind `--force`.
- **Frequency signal is coarse.** Hunspell-fr is a spell-checker
  dictionary, not a frequency corpus. The §4 fallback to
  alphabetical rank is honest but crude; difficulty quality
  improves materially only when a frequency source is layered on.

### Different

- **Schema lives in `grid-api`'s database.** Not a separate
  worker database. The worker writes; the API no longer reads from
  it in production. Flyway still applies the migration on startup
  so local-dev gets a usable `words` table without manual steps.
- **`fr.json` is deleted.** `words-fr.csv` is the sole
  authoritative source from this PR forward. The hand-curated 122
  entries seed the initial CSV; the Hunspell-fr corpus replaces
  them when the operator first runs the full import-words pipeline.
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
- **API-level caching of clues.** The API reads from an in-memory
  map (populated at startup from the CSV) on every request; no
  external cache is needed for the current dataset size.
- **Attribution on the API response.** The `Clue.source` field
  in §1 is a future API contract change. This ADR commits to
  the column existing; it does not commit to the response shape.

### Follow-up implementation

This ADR originally unblocked five sequenced PRs. After the §8
amendment to a CSV-as-source-of-truth shape, the plan is four
PRs and a deferred follow-up:

1. **Schema migration PR (merged).** Flyway migration adding the
   `words` table per §3, applied locally by the worker on
   startup. The API used to apply this on startup too; under §8
   it stays bundled with `grid-api` for dev-parity but the CNPG
   cluster is no longer the API's read path.
2. **`import-words` sub-command PR (merged).** Hunspell-fr
   importer with §2 filters, §4 difficulty heuristic, idempotent
   on `(word, language)`.
3. **`generate-clues` sub-command PR (merged).** Claude-backed
   clue generator with the §5 prompt, retry/length-cap, `--force`.
4. **CSV reader + `export-words` PR (this PR).** Adds
   `export-words` (§7), swaps the API's `ResourceWordRepository`
   for a `CsvWordRepository` reading
   `grid/api/src/main/resources/words/words-fr.csv`, deletes
   `fr.json`. No flag, no fallback — see §8.

The previously-planned **PR5 (initial seed run + Dockerfile +
GHA image-push + CronJob)** is **dropped from the plan**. The
worker is now a local-dev tool, not a deployed service; there is
no in-cluster seed run, no worker image, no scheduled job.
Clue review happens on a developer machine, the resulting CSV is
reviewed in PR, and the deploy is the same one that ships
`grid-api`.

If/when the player-data difficulty path (Notes) requires
per-row writes from production, the API DB read path is worth
revisiting — that is the only known scenario in which the §8
CSV shape would need to be unwound.

PR boundaries follow the `CLAUDE.md` 400-line rule.
