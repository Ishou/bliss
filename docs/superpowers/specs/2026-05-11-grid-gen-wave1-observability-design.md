# Design — Grid generation Wave 1: observability & measurement

**Date:** 2026-05-11
**Status:** Proposed (awaiting user approval)
**Branch:** `feat/grid-gen-enhance`
**Companion plans:** `docs/superpowers/plans/2026-05-11-grid-gen-wave1-observability.md` (to be written by `writing-plans`).

## Context

The grid generation pipeline (`grid/domain/.../generation/`) has three phases:
`Skeleton` → `SlotPlanner` → `SkeletonFiller`. A self-critique of the current
implementation surfaced ~14 issues across planning, filling, and cross-cutting
concerns. The full list was decomposed into 7 sequential waves on the
`feat/grid-gen-enhance` branch:

1. **Wave 1 — Observability & measurement** (this spec)
2. Wave 2 — Hygiene & seams (`Clock` port, lemma/cooldown cleanup, injectable `validLengths`)
3. Wave 3 — Corpus-aware length selection (the largest expected leverage)
4. Wave 4 — Planner heuristics (MRV arrow ordering, incremental orphan tracking, …)
5. Wave 5 — Filler heuristics (AC-3, LCV, domain cache)
6. Wave 6 — Advanced CSP (backjumping, nogood recording)
7. Wave 7 — Skeleton structural variability

Waves 3–7 each change algorithm behaviour. Without a fast benchmark loop and
diversity / single-attempt-success metrics, we can't tell whether a change
helped, hurt, or just shifted weight. Wave 1 lays that foundation.

Three concrete gaps motivate Wave 1:

1. **No fast bench.** The existing `GridGenBenchmarkTest` runs 200 generations
   per `@Test` — too slow for the dev-loop iteration the later waves need.
2. **No attempts-per-success metric.** `GenerationMetrics.succeeded` is
   per-single-attempt; the bench computes `successCount/n` only at the end.
   Neither captures the retry-loop number (average attempts per produced
   grid) — the *algorithm-visible* signal that says "your fat tail got fatter"
   even when wall time looks fine because retries absorb it.
3. **No shape-diversity metric.** The recent descending-length change
   ([`docs/superpowers/plans/2026-05-11-favor-long-slot-lengths.md`](../plans/2026-05-11-favor-long-slot-lengths.md))
   risks collapsing the per-`(width, height)` grid-shape distribution to one
   or two canonical forms. We have no signal for this today.

## Goals

- Add a **25-generation fast bench** (dev-loop) and **100-generation PR-gate
  bench**, both reusing the existing per-phase metric extraction.
- Add a **shape-hash diversity metric** (canonical fingerprint over
  `(clue positions, slot lengths)`, words excluded). Report collision rate
  across each bench run.
- Add an **attempts-per-success aggregator** at the application layer
  (`AttemptOutcome`) so the retry-loop signal is first-class.
- Add a **bench diff helper** that turns "baseline CSV vs current CSV" into a
  one-line table — the canonical "before/after" artefact every subsequent
  wave attaches to its PR.
- Establish the **per-wave protocol** (run baseline, run current, run diff)
  documented inline in this spec.

## Non-goals

- **Production observability.** OTel metrics, structured per-attempt logs,
  correlation IDs. Wave 1 is bench-and-test only. Defer to a later wave.
- **Per-phase budget *policy*.** We only *observe* per-phase split here;
  enforcing "planner gets 30% of deadline" is policy and lives in a later
  wave.
- **Word-level diversity.** Shape diversity only — word-collision tracking
  comes after Wave 3 (where corpus-feedback would actually move that needle).
- **Any change to `GenerationMetrics` fields.** We *consume* what's already
  there. No additions, no renames.
- **Any algorithm change.** Wave 1 is observation-only. The 100-gen bench
  numbers on `main` and at the end of Wave 1 should be identical modulo
  noise — verified as part of the wave's exit criterion.
- **Replacing or removing the existing 200-gen `@Test`.** It stays for parity
  / legacy comparison.

## Architecture

The four pieces split across the existing bounded-context layers cleanly:

| Piece | Layer | New file(s) |
|---|---|---|
| Fast bench tests (25-gen, 100-gen) | `grid/api` (test) | extends existing `GridGenBenchmarkTest.kt` |
| `GridShapeHash` | `grid/domain` | `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/GridShapeHash.kt` (+ test) |
| `AttemptOutcome` + `executeWithOutcome` | `grid/application` | `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/AttemptOutcome.kt`; modify `GeneratePuzzleUseCase.kt` |
| Bench diff helper | `grid/api` (test) | `grid/api/src/test/kotlin/com/bliss/grid/api/BenchDiff.kt` (+ test) |

Konsist architecture rules are unaffected:
- `GridShapeHash` is a pure function in `domain/generation/`. Zero deps.
- `AttemptOutcome` is a pure data class in `application/puzzle/`.
- No new cross-context imports. No new vendor SDK in domain/application.

## Components

### `GridShapeHash` (domain)

```kotlin
// grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/GridShapeHash.kt
object GridShapeHash {
    /** Pre-fill fingerprint from planner output, before any words land. */
    fun of(slots: List<WordSlot>): String

    /** Post-fill fingerprint from a finished Grid via its placements. */
    fun of(grid: Grid): String
}
```

**Canonical encoding:**
1. Extract slot tuples `(row, col, direction, length)` — from `slots` directly
   or from `grid.placements.map { (it.cluePosition.row, it.cluePosition.column, it.direction, it.word.text.length) }`.
2. Sort tuples lexicographically.
3. Join as `"r,c,dir,len|r,c,dir,len|..."`.
4. SHA-1 hex over UTF-8 bytes.

SHA-1 chosen for low collision probability at zero crypto cost. We only need
a stable fingerprint for diversity tracking, not a security primitive.

### `AttemptOutcome` (application)

```kotlin
// grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/AttemptOutcome.kt
data class AttemptOutcome(
    val grid: Grid?,
    val attempts: Int,                              // total attempts, success or fail
    val perAttemptMs: List<Long>,                   // wall time of each attempt, in order
    val perAttemptMetrics: List<GenerationMetrics>, // per-attempt metric snapshots, in order
    val totalMs: Long,                              // sum of perAttemptMs
) {
    val succeeded: Boolean get() = grid != null
}
```

### `GeneratePuzzleUseCase` change

Add a sibling method:

```kotlin
fun executeWithOutcome(
    width: Int? = null,
    height: Int? = null,
    cooldownPolicy: ClueCooldownPolicy = ClueCooldownPolicy.Inert,
): AttemptOutcome
```

`executeWithOutcome` contains today's `execute()` retry loop, additionally:
- Builds a `GenerationMetrics` per attempt and appends it to the list.
- Tracks each attempt's wall time and appends to the list.
- Returns the aggregated `AttemptOutcome` once a grid is produced or
  `maxAttempts` is exhausted.

Existing `execute(...)` becomes a one-liner:
```kotlin
fun execute(...): Grid? = executeWithOutcome(...).grid
```

No behavioural change for current callers. The Ktor routes continue to call
`execute()`.

### Fast bench `@Test` methods

Add two `@Test fun` methods to `GridGenBenchmarkTest` (same `@Tag("bench")`,
same opt-in via `-Dincludetags=bench`):

```kotlin
@Test fun `25 puzzles fast iteration bench`()
@Test fun `100 puzzles PR-gate bench`()
```

Both delegate to a shared private helper:

```kotlin
private fun runBench(n: Int, label: String)
```

`runBench` semantics:
1. Build `CsvWordRepository.frenchFromClasspath()` and a `GeneratePuzzleUseCase`
   with default constraints.
2. Warm-up loop of `WARMUP_N` (existing constant, currently 5) — unchanged.
3. Loop `n` puzzles with `Random(i * 1000L)` for determinism.
4. Call `useCase.executeWithOutcome()` for each iteration.
5. Compute `shapeHash = if (outcome.grid != null) GridShapeHash.of(outcome.grid) else "FAIL"`.
6. Append CSV row with columns:
   `seed,total_ms,attempts,skeleton_ms,slot_plan_ms,fill_ms,slot_plan_backtracks,fill_backtracks,fill_repo_calls,fill_first_slot_domain,shape_hash,succeeded`.
   Per-phase metrics come from the **last attempt's** `GenerationMetrics`
   snapshot (the one that produced the grid, or the last failing one).
7. After loop: log existing per-metric distribution lines plus a new
   `bench_shape_collisions n={n} unique={u} collisions={n-u}` line.
8. Write CSV to `data/eval/grid_gen_bench_${label}_${timestamp}.csv`.

The existing 200-gen `@Test fun` is **not modified** — it stays alongside the
new methods for legacy comparison.

### `BenchDiff` (api test)

```kotlin
// grid/api/src/test/kotlin/com/bliss/grid/api/BenchDiff.kt
object BenchDiff {
    /** Reads both CSVs, returns a side-by-side comparison table as a multi-line string. */
    fun compare(baseline: Path, current: Path): String
}
```

Output format (one fenced code block, monospace columns):

```
metric                 baseline    current     delta    flag
total_ms p50           450         380         -16%     ▼
total_ms p95           2100        1850        -12%     ▼
total_ms p99           4800        2200        -54%     ▼
attempts_per_puzzle    1.6         1.2         -25%     ▼
shape_collision_rate   0.32        0.08        -75%     ▼
success_rate           0.95        1.00        +5%      ▼
skeleton_ms p50        1           1           0%
slot_plan_ms p50       3           4           +33%     ▲
fill_ms p50            440         370         -16%     ▼
```

Flag column: `▼` for ≥10% improvement, `▲` for ≥10% regression, blank otherwise.

Exposed via a third `@Test fun` that reads paths from JVM properties:

```kotlin
@Test fun `compare baseline vs current`() {
    val baseline = System.getProperty("bench.baseline")
        ?: return  // skipped via Assumptions.assumeTrue
    val current = System.getProperty("bench.current")
        ?: return
    log.info(BenchDiff.compare(Path.of(baseline), Path.of(current)))
}
```

Missing properties → test is skipped (not failed), so the bench tag's normal
runs don't break.

## Data flow

```
runBench(n=100, label="pr-gate") loop iteration i:
  ├─ random = Random(i * 1000L)            // deterministic seed-per-iter
  ├─ outcome = useCase.executeWithOutcome()
  │     └─ retries internally up to maxAttempts;
  │        builds a fresh GenerationMetrics per attempt and appends both
  │        metrics and elapsed-ms to outcome's lists.
  ├─ lastMetrics = outcome.perAttemptMetrics.last()
  ├─ shapeHash = if (outcome.grid != null) GridShapeHash.of(outcome.grid)
  │              else "FAIL"
  ├─ csvRow(seed, outcome.totalMs, outcome.attempts, lastMetrics.skeletonMs,
  │         lastMetrics.slotPlanMs, lastMetrics.fillMs, …, shapeHash,
  │         outcome.succeeded)
  └─ collect into results list

after loop:
  ├─ logDistribution("total_ms", …)         // existing helper
  ├─ logDistribution("attempts_per_puzzle", …) // new
  ├─ shape_collisions = n − results.map{it.shapeHash}.distinct().size
  ├─ log "bench_shape_collisions n=$n unique=$unique collisions=$collisions"
  └─ write CSV to data/eval/grid_gen_bench_${label}_${ts}.csv
```

## Per-wave protocol

Every subsequent wave (3 through 7) runs this protocol and attaches the
diff-table output to its PR description.

```bash
# 1. Baseline: run on main
git checkout main
./gradlew :grid:api:test \
  --tests "*PR-gate bench*" \
  -Dincludetags=bench
# CSV lands at data/eval/grid_gen_bench_pr-gate_<ts>.csv
mv data/eval/grid_gen_bench_pr-gate_*.csv data/eval/baseline_wave-N.csv

# 2. Current: run on wave branch (or wave commit on feat/grid-gen-enhance)
git checkout feat/grid-gen-enhance     # at the wave's tip commit
./gradlew :grid:api:test \
  --tests "*PR-gate bench*" \
  -Dincludetags=bench
mv data/eval/grid_gen_bench_pr-gate_*.csv data/eval/current_wave-N.csv

# 3. Diff
./gradlew :grid:api:test \
  --tests "*compare baseline vs current*" \
  -Dincludetags=bench \
  -Dbench.baseline=data/eval/baseline_wave-N.csv \
  -Dbench.current=data/eval/current_wave-N.csv
# → paste the table into the PR description
```

During wave development the **25-gen fast bench** is the dev-loop signal
(catch >2× regressions / wins, sub-minute turnaround). The **100-gen
PR-gate bench** is the artefact attached to the PR.

## `.gitignore` policy

Bench CSVs are machine-specific dev artefacts. Add to root `.gitignore`:
```
data/eval/grid_gen_bench_*.csv
data/eval/baseline_wave-*.csv
data/eval/current_wave-*.csv
```
Verify the existing 200-gen pattern is already ignored before committing.

## Error handling

- **Repository load failure** in bench → propagates as `IOException`, test
  fails with stack trace. No fallback; the bench is opt-in.
- **`executeWithOutcome` returns grid=null** (all attempts failed) → bench
  still records the row with `shape_hash="FAIL"`, `succeeded=false`. Does not
  short-circuit the bench loop.
- **Empty `perAttemptMetrics`** in an `AttemptOutcome` is treated as a
  programming bug (every retry must record a snapshot) — `runBench` reads
  `.last()` which would throw `NoSuchElementException`. We let it throw;
  this is a contract violation, not a recoverable condition.
- **`BenchDiff.compare` missing column** in one CSV → cell renders as `n/a`,
  delta as `n/a`, no flag. Not a crash.
- **`BenchDiff` malformed CSV** (header mismatch, non-numeric value where
  numeric expected) → `IllegalArgumentException` with the offending row's
  index. Caller (the `@Test`) fails with a clear message.

## Testing strategy

| Component | Test type | File |
|---|---|---|
| `GridShapeHash` | Unit | `grid/domain/src/test/.../generation/GridShapeHashTest.kt` |
| `AttemptOutcome` | None (trivial data class) | — |
| `GeneratePuzzleUseCase.executeWithOutcome` | Unit | extend `grid/application/src/test/.../puzzle/GeneratePuzzleUseCaseTest.kt` |
| Fast bench `@Test` methods | Manual (`@Tag("bench")`, not in CI) | `grid/api/src/test/.../GridGenBenchmarkTest.kt` |
| `BenchDiff.compare` | Unit (two fixture CSVs) | `grid/api/src/test/.../BenchDiffTest.kt` |

### `GridShapeHashTest` contracts

- **Order-independence:** the same set of slots in two different list orders
  produces the same hash.
- **Length sensitivity:** same skeleton, different lengths → different hash.
- **Position sensitivity:** same length set, different clue positions →
  different hash.
- **Cross-form agreement:** `of(grid)` equals `of(slots)` when `grid` is
  built from `slots`-derived placements (verified using
  `Grid.fromPlacements`).
- **Determinism:** same input → same hash across JVM invocations
  (no `hashCode()`-based ordering).
- **FAIL sentinel disjointness:** any real hash differs from the literal
  string `"FAIL"`.

### `GeneratePuzzleUseCase.executeWithOutcome` contracts

- **Single-attempt success:** outcome has `attempts == 1`,
  `perAttemptMs.size == 1`, `perAttemptMetrics.size == 1`, `grid != null`,
  `succeeded == true`.
- **All-attempts failure:** with `maxAttempts = 2` against a corpus too
  small to fill anything, outcome has `attempts == 2`, `grid == null`,
  `succeeded == false`, both attempts captured in `perAttemptMs` and
  `perAttemptMetrics`.
- **`totalMs` invariant:** `outcome.totalMs == outcome.perAttemptMs.sum()`.
- **`execute` parity:** `execute()` returns the same `Grid?` as
  `executeWithOutcome().grid` for the same seed (regression guard on the
  wrapper extraction).

### `BenchDiffTest` contracts

- **Identical CSVs:** every delta is `0%`, no flag.
- **2× regression in `total_ms p50`:** delta is `+100%`, flag is `▲`.
- **2× improvement in `total_ms p50`:** delta is `-50%`, flag is `▼`.
- **Missing column in one CSV:** that row renders `n/a`, comparison
  continues for other rows.
- **Different row counts:** each CSV's percentiles are computed
  independently; no row-by-row alignment is assumed.

## Exit criteria

A wave is "done" when all of these hold:

1. All commits land on `feat/grid-gen-enhance`, one commit per implementation
   step, conventional-commit format.
2. `./gradlew :grid:domain:test :grid:application:test :grid:api:build`
   passes. Konsist arch tests still pass. Spotless clean.
3. Running the 100-gen PR-gate bench on `main` and at the tip of Wave 1
   produces **statistically indistinguishable** numbers (no algorithm
   change). Concretely: p50 total_ms within ±10%, success rate within
   ±5%, no shape-collision-rate regression.
4. The Wave 1 PR description (when sliced for merge) carries:
   - The output of `BenchDiff.compare(baseline=main, current=wave1-tip)`
     showing the no-change result.
   - The 100-gen CSV from `main` and from `wave1-tip` (as PR attachments,
     not committed — see `.gitignore` policy).

## Open questions

None — all design calls were made during brainstorming and approved by the
user inline.

## References

- Self-critique conversation (this branch's brainstorm).
- Existing benchmark: `grid/api/src/test/kotlin/com/bliss/grid/api/GridGenBenchmarkTest.kt`.
- Existing metrics: `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/GenerationMetrics.kt`.
- Recent length-ordering change: `docs/superpowers/plans/2026-05-11-favor-long-slot-lengths.md`.
- Manifesto rules on PR size, conventional commits, bounded contexts: `CLAUDE.md`.
