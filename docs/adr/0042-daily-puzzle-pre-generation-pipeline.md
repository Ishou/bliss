# ADR-0042: Daily puzzle pre-generation pipeline (amends ADR-0013)

## Status

Accepted.

## Context

ADR-0013 §8 (amended 2026-05-08) retired the `grid/worker` deployed service: the
word-clue pipeline moved to a local-dev CSV workflow and no CronJob or worker image
was needed. Its Consequences section explicitly states: "no in-cluster seed run, no
worker image, no scheduled job."

Post-ADR-0039 (bitmask CSP generator), daily-puzzle generation now runs the CSP
solver with seed iteration. In the long tail (~15 % of attempts) the solver takes
several seconds to converge. Today's `/v1/puzzles/daily` route runs generation on
the request thread, so that latency surfaces directly to the user.

A rolling pre-generation pass — run off-band before the user request arrives — would
keep the hot path to a simple Postgres read. The question is where and how to run it.

Options considered:

1. **In-request lazy generation with a short timeout + async retry** — keeps the
   current single-service shape but adds complexity around partial failures and
   timeout semantics. A slow seed on day 0 still blocks the first user of the day.

2. **Re-deploy `grid/worker` as a Kubernetes CronJob** — matches the original ADR-0013
   §7 intent before the CSV-pivot amendment. The module already exists; only the
   deployment artifact and the use case differ from the word-clue pipeline.

3. **Move generation into a Ktor background coroutine inside `grid/api`** — avoids a
   new deploy target but complicates the API pod's lifecycle (startup time, OOM
   pressure during generation, shared-process failure modes).

Option 2 is chosen. The worker image is a thin wrapper over the existing application
and infrastructure layers; it adds no new bounded-context dependencies. Option 3
trades deployment simplicity for process coupling that violates dev/prod parity when
the API scales to multiple replicas (each pod would generate independently,
redundantly consuming cooldown budget).

## Decision

### 1. Re-deploy `grid/worker` as a Kubernetes CronJob (amends ADR-0013)

ADR-0013's "no deployed service" Consequences clause is superseded for the
daily-generation use case. The `grid/worker` module gains a production deployment
artifact: a shadowJar Docker image driven by the `--ensure-dailies` CLI command.
The ADR-0013 word-clue pipeline (import-words, generate-clues, export-words) remains
a local-dev tool — that part of ADR-0013 is unchanged.

### 2. Rolling 7-day window

The worker pre-generates puzzles for `[today, today+6]` (UTC), skipping dates that
already have a persisted puzzle. Seven days provides a comfortable buffer against
CronJob skips or transient Postgres failures without over-generating.

### 3. Sequential execution

Days are processed strictly in order. Each successful generation may bump the
`DAILY_SCOPE_ID` cooldown counter via `ClueCooldownRepository.recordGeneration`
(ADR-0031). The next day's generator reads that updated snapshot to bias clue picks.
Parallel execution would interleave cooldown reads and writes and corrupt the
ordering.

### 4. Seed iteration scheme

`randomSeed = date.toEpochDay() * 1000 + attempt` for `attempt in 0..<20`. The
stride of 1000 ensures seeds for adjacent days never collide within the 20-attempt
budget. The puzzle ID for each date remains deterministic (`DailyPuzzleSelector`
is unchanged). Only the generator's randomisation varies across attempts.

### 5. Exit-code contract

- **0** — every date in the window is persisted (pre-existing or freshly generated).
- **1** — at least one date exhausted all attempts and remains ungenerated. The
  CronJob treats exit 1 as an alertable signal (non-zero exit → pod failure →
  Kubernetes alerts on CronJob failure).
- **2** — invalid CLI invocation.

### 6. 20-attempt budget

20 attempts per date is chosen to match `GeneratePuzzleUseCase`'s historical
convergence rate for the bitmask CSP (ADR-0039): >99 % of seeds converge in ≤10
attempts on the production corpus. 20 provides a headroom factor of ~2×.

### 7. Words corpus

`CsvWordRepository.frenchFromClasspath()` reads `words/words-fr.csv` from the
classpath. The shadowJar bundles the corpus via a Gradle `copyWordsCorpus` task
that stages `:grid:api`'s `words/` resources into the worker's resource output.
This single-sources the corpus on disk; updates to the CSV propagate to both the
API and the worker images on the next build.

## Consequences

### Easier

- `/v1/puzzles/daily` becomes a Postgres read on the hot path; generation latency
  moves entirely off-band.
- CronJob failure (exit 1) surfaces as a Kubernetes event and triggers an alert
  before the user would notice a missing puzzle.
- The rolling 7-day window means a single skipped CronJob run is self-healing: the
  next run fills the gap before the gap date arrives.

### Harder

- A second deploy artifact (worker image) must be built, pushed, and kept in sync
  with `grid/api`'s dependency versions.
- The `game/api/Dockerfile` does not include `grid/worker/build.gradle.kts` in its
  Gradle configuration-phase COPY set; the `grid/api/Dockerfile` does. If
  `settings.gradle.kts` includes `:grid:worker`, the `game/api` image build must
  also COPY that build script. This cross-context Dockerfile coupling is tracked as
  a follow-up `chore(game):` PR to keep that change in the correct bounded-context
  workstream.

### Different

- ADR-0013's Consequences clause "no in-cluster seed run, no worker image, no
  scheduled job" no longer applies to the `--ensure-dailies` command path.
  The word-clue pipeline commands (import-words, generate-clues, export-words) remain
  local-dev only and are unaffected by this ADR.
