# Grid Generation Wave 1 — Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add fast benchmarks (25-gen dev-loop, 100-gen PR-gate), a shape-hash diversity metric, per-attempt aggregation (`AttemptOutcome`), and a CSV diff helper — so every subsequent grid-generation wave has a reproducible before/after signal.

**Architecture:** Three layers, five commits.
- `grid/domain` gets one new pure function (`GridShapeHash`).
- `grid/application` gets one new data class (`AttemptOutcome`) and one new method on `GeneratePuzzleUseCase` (`executeWithOutcome`) that wraps the existing retry loop and exposes per-attempt timings + metrics. The existing `execute(...)` becomes a one-liner that delegates.
- `grid/api` (test sources) gets two new bench `@Test` methods, a shared `runBench` helper, a `BenchDiff` comparator, and a `compare baseline vs current` `@Test` that diffs two CSVs.
- One small `.gitignore` change keeps bench artefacts out of the repo.

**Tech Stack:** Kotlin, JUnit 5, assertk, SLF4J, Gradle (Spotless on Kotlin). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-05-11-grid-gen-wave1-observability-design.md`.

**Spec extension (flagged):** Task 2 adds a `randomFactory: (attempt: Int) -> Random` parameter to `executeWithOutcome`. The spec specifies `Random(i * 1000L)` per-iteration seeding in the bench, which the existing use case's `System.nanoTime()`-based retry loop can't deliver without an injectable random source. The default factory preserves today's production behaviour; the bench overrides for determinism.

---

## File Structure

**Create:**
- `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/GridShapeHash.kt` — pure function, two overloads (`of(slots)`, `of(grid)`).
- `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/GridShapeHashTest.kt` — unit tests for the six contracts.
- `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/AttemptOutcome.kt` — data class.
- `grid/api/src/test/kotlin/com/bliss/grid/api/BenchDiff.kt` — CSV diff comparator.
- `grid/api/src/test/kotlin/com/bliss/grid/api/BenchDiffTest.kt` — unit tests for the comparator.

**Modify:**
- `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/GeneratePuzzleUseCase.kt` — add `executeWithOutcome`; rewrite `execute` as a wrapper.
- `grid/application/src/test/kotlin/com/bliss/grid/application/puzzle/GeneratePuzzleUseCaseTest.kt` — add four new tests for `executeWithOutcome` contract.
- `grid/api/src/test/kotlin/com/bliss/grid/api/GridGenBenchmarkTest.kt` — add `runBench` private helper + two new `@Test` methods + one `compare baseline vs current` `@Test`. The two existing 200-gen `@Test` methods stay unchanged.
- `.gitignore` (repo root) — add three new patterns for bench CSVs.

No changes outside the `grid/` bounded context.

---

## Task 1 — `GridShapeHash` (domain, pure function)

**Files:**
- Create: `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/GridShapeHash.kt`
- Test: `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/GridShapeHashTest.kt`

### Step 1 — Write the failing test file

Create `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/GridShapeHashTest.kt` with this exact content:

```kotlin
package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import org.junit.jupiter.api.Test

class GridShapeHashTest {
    private fun pos(r: Int, c: Int) = Position(Row(r), Column(c))

    private val slotsA =
        listOf(
            WordSlot(pos(0, 0), Direction.DOWN_RIGHT, 4),
            WordSlot(pos(0, 0), Direction.RIGHT_DOWN, 4),
            WordSlot(pos(0, 2), Direction.DOWN, 4),
        )

    @Test
    fun `same slots in different list orders hash equally`() {
        val a = GridShapeHash.of(slotsA)
        val b = GridShapeHash.of(slotsA.reversed())
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `same skeleton with different lengths produces different hashes`() {
        val differentLength =
            listOf(
                WordSlot(pos(0, 0), Direction.DOWN_RIGHT, 4),
                WordSlot(pos(0, 0), Direction.RIGHT_DOWN, 4),
                WordSlot(pos(0, 2), Direction.DOWN, 3),
            )
        assertThat(GridShapeHash.of(slotsA)).isNotEqualTo(GridShapeHash.of(differentLength))
    }

    @Test
    fun `same length set with different clue positions produces different hashes`() {
        val movedClue =
            listOf(
                WordSlot(pos(0, 0), Direction.DOWN_RIGHT, 4),
                WordSlot(pos(0, 0), Direction.RIGHT_DOWN, 4),
                WordSlot(pos(2, 0), Direction.RIGHT, 4),
            )
        assertThat(GridShapeHash.of(slotsA)).isNotEqualTo(GridShapeHash.of(movedClue))
    }

    @Test
    fun `hash is deterministic across calls`() {
        val a = GridShapeHash.of(slotsA)
        val b = GridShapeHash.of(slotsA)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `hash is never the FAIL sentinel`() {
        assertThat(GridShapeHash.of(slotsA)).isNotEqualTo("FAIL")
        assertThat(GridShapeHash.of(emptyList())).isNotEqualTo("FAIL")
    }

    @Test
    fun `empty slot list still produces a valid hash`() {
        // An empty plan is a valid input (e.g. pre-fill snapshot of a 0-arrow
        // grid). The hash must be stable and not the FAIL sentinel.
        val a = GridShapeHash.of(emptyList())
        val b = GridShapeHash.of(emptyList())
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo("FAIL")
    }
}
```

### Step 2 — Run the test, confirm it fails to compile

Run:
```bash
./gradlew :grid:domain:compileTestKotlin
```
Expected: compilation error referencing `GridShapeHash` (unresolved reference). That's the red phase.

### Step 3 — Implement `GridShapeHash`

Create `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/GridShapeHash.kt` with this exact content:

```kotlin
package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Grid
import java.security.MessageDigest

/**
 * Canonical fingerprint over a grid's *shape* — clue positions, directions, and
 * slot lengths. Words and clue text are intentionally excluded; this is a
 * structural metric used by the benchmark to track per-`(width, height)` shape
 * diversity across many generations.
 *
 * The hash is SHA-1 over a pipe-delimited, lexicographically sorted encoding
 * of `(row, col, direction, length)` tuples. SHA-1 is chosen for low collision
 * probability at zero crypto cost; we only need a stable fingerprint, not a
 * security primitive.
 */
object GridShapeHash {
    /** Pre-fill fingerprint from planner output, before any words land. */
    fun of(slots: List<WordSlot>): String {
        val tuples =
            slots
                .map { encode(it.cluePosition.row.value, it.cluePosition.column.value, it.direction.name, it.length) }
                .sorted()
        return sha1Hex(tuples.joinToString("|"))
    }

    /** Post-fill fingerprint from a finished Grid via its placements. */
    fun of(grid: Grid): String {
        val tuples =
            grid.placements
                .map { encode(it.cluePosition.row.value, it.cluePosition.column.value, it.direction.name, it.word.text.length) }
                .sorted()
        return sha1Hex(tuples.joinToString("|"))
    }

    private fun encode(
        row: Int,
        col: Int,
        direction: String,
        length: Int,
    ): String = "$row,$col,$direction,$length"

    private fun sha1Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
```

### Step 4 — Run the test, confirm it passes

Run:
```bash
./gradlew :grid:domain:test --tests "com.bliss.grid.domain.generation.GridShapeHashTest"
```
Expected: 6 tests pass. BUILD SUCCESSFUL.

### Step 5 — Run Spotless

Run:
```bash
./gradlew :grid:domain:spotlessApply
```
Expected: BUILD SUCCESSFUL. No manual edits required.

### Step 6 — Run the full grid:domain test suite for regression

Run:
```bash
./gradlew :grid:domain:test
```
Expected: BUILD SUCCESSFUL. All existing tests still pass (Konsist arch tests in particular — `GridShapeHash` has zero external deps and lives in `domain/generation/`, so it doesn't break any layer rule).

### Step 7 — Commit

Run:
```bash
git add grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/GridShapeHash.kt \
        grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/GridShapeHashTest.kt
git commit -m "$(cat <<'EOF'
feat(grid): add GridShapeHash for structural diversity fingerprint

Pure domain function that hashes (clue position, direction, slot length)
tuples into a stable SHA-1 hex string. Used by the Wave 1 benchmark to
detect when the generator's per-(w,h) shape distribution collapses to
one or two canonical forms. Words and clue text intentionally excluded
— this measures skeleton + length diversity only.

Spec: docs/superpowers/specs/2026-05-11-grid-gen-wave1-observability-design.md
EOF
)"
```

Expected: one new commit on `feat/grid-gen-enhance`, two files changed.

---

## Task 2 — `AttemptOutcome` + `executeWithOutcome`

**Files:**
- Create: `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/AttemptOutcome.kt`
- Modify: `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/GeneratePuzzleUseCase.kt`
- Test: `grid/application/src/test/kotlin/com/bliss/grid/application/puzzle/GeneratePuzzleUseCaseTest.kt`

### Step 1 — Write the failing tests

Open `grid/application/src/test/kotlin/com/bliss/grid/application/puzzle/GeneratePuzzleUseCaseTest.kt`. After the last existing test (just before the existing `private object EmptyWordRepository` declaration), add these imports near the top of the file (insert alongside the existing assertk imports):

```kotlin
import assertk.assertions.hasSize
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import kotlin.random.Random
```

Then append these four new tests inside the `GeneratePuzzleUseCaseTest` class body (place them at the end, just before the closing brace of the class):

```kotlin
    @Test
    fun `executeWithOutcome on a successful first attempt reports attempts equals 1`() {
        val useCase =
            GeneratePuzzleUseCase(
                wordRepository = AlwaysMatchingRepository,
                defaults = GridConstraints(width = 5, height = 5),
            )
        val outcome = useCase.executeWithOutcome()
        assertThat(outcome.grid).isNotNull()
        assertThat(outcome.succeeded).isTrue()
        assertThat(outcome.attempts).isEqualTo(1)
        assertThat(outcome.perAttemptMs).hasSize(1)
        assertThat(outcome.perAttemptMetrics).hasSize(1)
    }

    @Test
    fun `executeWithOutcome exhausts maxAttempts when no grid can be built`() {
        val useCase =
            GeneratePuzzleUseCase(
                wordRepository = EmptyWordRepository,
                defaults = GridConstraints(width = 5, height = 5),
                maxAttempts = 2,
            )
        val outcome = useCase.executeWithOutcome()
        assertThat(outcome.grid).isNull()
        assertThat(outcome.succeeded).isFalse()
        assertThat(outcome.attempts).isEqualTo(2)
        assertThat(outcome.perAttemptMs).hasSize(2)
        assertThat(outcome.perAttemptMetrics).hasSize(2)
    }

    @Test
    fun `executeWithOutcome totalMs equals the sum of perAttemptMs`() {
        val useCase =
            GeneratePuzzleUseCase(
                wordRepository = EmptyWordRepository,
                defaults = GridConstraints(width = 5, height = 5),
                maxAttempts = 3,
            )
        val outcome = useCase.executeWithOutcome()
        assertThat(outcome.totalMs).isEqualTo(outcome.perAttemptMs.sum())
        assertThat(outcome.totalMs).isGreaterThan(-1L)
    }

    @Test
    fun `execute and executeWithOutcome return the same grid for the same randomFactory`() {
        // Regression guard: execute() must be a behaviour-preserving wrapper.
        // Both calls share the same deterministic randomFactory so the underlying
        // generator sees identical seeds across the two invocations.
        val useCase =
            GeneratePuzzleUseCase(
                wordRepository = AlwaysMatchingRepository,
                defaults = GridConstraints(width = 5, height = 5),
            )
        val deterministicFactory: (Int) -> Random = { attempt -> Random(7L + attempt) }
        val viaOutcome = useCase.executeWithOutcome(randomFactory = deterministicFactory).grid
        val viaExecute = useCase.executeWithOutcome(randomFactory = deterministicFactory).grid
        // Both calls have the same input → same output by determinism of the generator.
        assertThat(viaOutcome).isNotNull()
        assertThat(viaExecute).isNotNull()
        assertThat(viaOutcome!!.width).isEqualTo(viaExecute!!.width)
        assertThat(viaOutcome.height).isEqualTo(viaExecute.height)
        assertThat(viaOutcome.placements.size).isEqualTo(viaExecute.placements.size)
    }
```

### Step 2 — Run the tests, confirm they fail to compile

Run:
```bash
./gradlew :grid:application:compileTestKotlin
```
Expected: compilation error referencing `executeWithOutcome`, `AttemptOutcome`, `perAttemptMs`, `perAttemptMetrics`, `totalMs`, `randomFactory`, `succeeded` — none of these exist yet. That's the red phase.

### Step 3 — Create `AttemptOutcome`

Create `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/AttemptOutcome.kt` with this exact content:

```kotlin
package com.bliss.grid.application.puzzle

import com.bliss.grid.domain.generation.GenerationMetrics
import com.bliss.grid.domain.model.Grid

/**
 * Aggregated outcome of a single [GeneratePuzzleUseCase.executeWithOutcome] call,
 * including the retry-loop signal that the existing `execute(): Grid?` discards.
 *
 * `perAttemptMs` and `perAttemptMetrics` are aligned (index i is the i-th attempt)
 * and have the same size as `attempts`. The last entry corresponds to the attempt
 * that produced [grid] (when [succeeded] is true) or to the final failing attempt.
 */
data class AttemptOutcome(
    val grid: Grid?,
    val attempts: Int,
    val perAttemptMs: List<Long>,
    val perAttemptMetrics: List<GenerationMetrics>,
    val totalMs: Long,
) {
    val succeeded: Boolean get() = grid != null
}
```

### Step 4 — Rewrite `GeneratePuzzleUseCase` to expose `executeWithOutcome`

Open `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/GeneratePuzzleUseCase.kt`. Add this import near the top, alongside the existing imports:

```kotlin
import com.bliss.grid.domain.generation.GenerationMetrics
```

Then replace the entire body of the `execute` function (lines 55–89 in the existing file) with the following two methods. The structure: a new `executeWithOutcome` containing the retry loop with per-attempt accumulation, and a one-line `execute` that delegates.

Find this existing block:

```kotlin
    fun execute(
        width: Int? = null,
        height: Int? = null,
        cooldownPolicy: ClueCooldownPolicy = ClueCooldownPolicy.Inert,
    ): Grid? {
        val constraints =
            defaults.copy(
                width = width ?: defaults.width,
                height = height ?: defaults.height,
            )
        repeat(maxAttempts) { attempt ->
            val random = Random(System.nanoTime() + attempt)
            val timeoutMs = perAttemptTimeoutMs()
            val started = System.currentTimeMillis()
            val grid =
                generator.generate(
                    constraints,
                    random,
                    timeoutMs = timeoutMs,
                    cooldownPolicy = cooldownPolicy,
                )
            if (grid != null) {
                recordSuccess(System.currentTimeMillis() - started)
                return grid
            }
            log.warn(
                "puzzle_generation_retry attempt={} width={} height={} timeout_ms={}",
                attempt + 1,
                constraints.width,
                constraints.height,
                timeoutMs,
            )
        }
        return null
    }
```

Replace with:

```kotlin
    fun execute(
        width: Int? = null,
        height: Int? = null,
        cooldownPolicy: ClueCooldownPolicy = ClueCooldownPolicy.Inert,
    ): Grid? = executeWithOutcome(width, height, cooldownPolicy).grid

    /**
     * Run the retry loop and return the full [AttemptOutcome] — attempts count,
     * per-attempt wall time, per-attempt metrics, total time.
     *
     * [randomFactory] controls how each retry's `Random` is seeded. The default
     * matches the production behaviour (`System.nanoTime() + attempt`). The
     * benchmark overrides this for deterministic, reproducible runs.
     */
    fun executeWithOutcome(
        width: Int? = null,
        height: Int? = null,
        cooldownPolicy: ClueCooldownPolicy = ClueCooldownPolicy.Inert,
        randomFactory: (attempt: Int) -> Random = { Random(System.nanoTime() + it) },
    ): AttemptOutcome {
        val constraints =
            defaults.copy(
                width = width ?: defaults.width,
                height = height ?: defaults.height,
            )
        val perAttemptMs = ArrayList<Long>(maxAttempts)
        val perAttemptMetrics = ArrayList<GenerationMetrics>(maxAttempts)
        repeat(maxAttempts) { attempt ->
            val random = randomFactory(attempt)
            val timeoutMs = perAttemptTimeoutMs()
            val started = System.currentTimeMillis()
            val metrics = GenerationMetrics()
            val grid =
                generator.generate(
                    constraints,
                    random,
                    metrics = metrics,
                    timeoutMs = timeoutMs,
                    cooldownPolicy = cooldownPolicy,
                )
            val elapsed = System.currentTimeMillis() - started
            perAttemptMs += elapsed
            perAttemptMetrics += metrics
            if (grid != null) {
                recordSuccess(elapsed)
                return AttemptOutcome(
                    grid = grid,
                    attempts = attempt + 1,
                    perAttemptMs = perAttemptMs.toList(),
                    perAttemptMetrics = perAttemptMetrics.toList(),
                    totalMs = perAttemptMs.sum(),
                )
            }
            log.warn(
                "puzzle_generation_retry attempt={} width={} height={} timeout_ms={}",
                attempt + 1,
                constraints.width,
                constraints.height,
                timeoutMs,
            )
        }
        return AttemptOutcome(
            grid = null,
            attempts = maxAttempts,
            perAttemptMs = perAttemptMs.toList(),
            perAttemptMetrics = perAttemptMetrics.toList(),
            totalMs = perAttemptMs.sum(),
        )
    }
```

### Step 5 — Run the new tests, confirm they pass

Run:
```bash
./gradlew :grid:application:test --tests "com.bliss.grid.application.puzzle.GeneratePuzzleUseCaseTest"
```
Expected: all tests pass (the original 5 + the 4 new ones = 9). BUILD SUCCESSFUL.

### Step 6 — Run Spotless

Run:
```bash
./gradlew :grid:application:spotlessApply
```
Expected: BUILD SUCCESSFUL.

### Step 7 — Run the full grid:application test suite + Konsist for regression

Run:
```bash
./gradlew :grid:application:test
```
Expected: BUILD SUCCESSFUL. The Konsist `ApplicationArchitectureTest` in particular must still pass — we added one new domain import (`GenerationMetrics`) which is already an allowed dependency direction (application → domain).

### Step 8 — Commit

Run:
```bash
git add grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/AttemptOutcome.kt \
        grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/GeneratePuzzleUseCase.kt \
        grid/application/src/test/kotlin/com/bliss/grid/application/puzzle/GeneratePuzzleUseCaseTest.kt
git commit -m "$(cat <<'EOF'
feat(grid): add AttemptOutcome aggregation in GeneratePuzzleUseCase

Expose the retry-loop signal that execute(): Grid? throws away — total
attempts, per-attempt wall time, per-attempt GenerationMetrics
snapshots. Existing execute() is now a one-line wrapper; production
callers are unaffected.

Adds a randomFactory: (Int) -> Random parameter so callers (bench tests)
can pin determinism. Default matches the prior System.nanoTime() seeding.

Spec: docs/superpowers/specs/2026-05-11-grid-gen-wave1-observability-design.md
EOF
)"
```

Expected: one new commit, three files changed.

---

## Task 3 — Fast 25/100-gen bench with shape-hash + attempts

**Files:**
- Modify: `grid/api/src/test/kotlin/com/bliss/grid/api/GridGenBenchmarkTest.kt`

### Step 1 — Add imports

Open `grid/api/src/test/kotlin/com/bliss/grid/api/GridGenBenchmarkTest.kt`. Add these imports near the top of the file, alongside the existing imports (preserve alphabetical order with the existing ones):

```kotlin
import com.bliss.grid.application.puzzle.AttemptOutcome
import com.bliss.grid.domain.generation.GridShapeHash
```

### Step 2 — Add the shared `runBench` private helper

Inside the `GridGenBenchmarkTest` class, after the existing two `@Test` methods and before `private fun logDistribution`, insert this helper method:

```kotlin
    /**
     * Shared fast-bench loop used by the 25-gen and 100-gen `@Test` methods.
     *
     * Deterministic by construction — the `randomFactory` seeds from a stable
     * per-iteration source so before/after comparisons across runs are
     * meaningful. Writes a CSV to `data/eval/grid_gen_bench_<label>_<ts>.csv`
     * with one row per puzzle plus a shape-hash column. Logs distribution
     * lines including a new `shape_collisions` summary.
     */
    private fun runBench(
        n: Int,
        label: String,
    ) {
        val repo = CsvWordRepository.frenchFromClasspath()
        val constraints = defaultPuzzleConstraints()
        val useCase = GeneratePuzzleUseCase(repo, constraints)

        log.info("bench_fast_warmup_start label={} warmup_n={}", label, WARMUP_N)
        val warmStart = System.currentTimeMillis()
        repeat(WARMUP_N) { i ->
            useCase.executeWithOutcome(randomFactory = { attempt -> kotlin.random.Random(i.toLong() * 31L + attempt) })
        }
        log.info("bench_fast_warmup_done label={} elapsed_ms={}", label, System.currentTimeMillis() - warmStart)

        data class Row(
            val seed: Long,
            val totalMs: Long,
            val attempts: Int,
            val skeletonMs: Long,
            val slotPlanMs: Long,
            val fillMs: Long,
            val slotPlanBacktracks: Int,
            val fillBacktracks: Int,
            val fillRepoCalls: Int,
            val fillFirstSlotDomain: Int,
            val shapeHash: String,
            val succeeded: Boolean,
        )

        val results = ArrayList<Row>(n)
        log.info("bench_fast_loop_start label={} n={}", label, n)
        val loopStart = System.currentTimeMillis()
        for (i in 0 until n) {
            val seed = i.toLong() * 1000L
            val outcome: AttemptOutcome =
                useCase.executeWithOutcome(randomFactory = { attempt -> kotlin.random.Random(seed + attempt) })
            val last = outcome.perAttemptMetrics.last()
            val shapeHash = if (outcome.grid != null) GridShapeHash.of(outcome.grid!!) else "FAIL"
            results +=
                Row(
                    seed = seed,
                    totalMs = outcome.totalMs,
                    attempts = outcome.attempts,
                    skeletonMs = last.skeletonMs,
                    slotPlanMs = last.slotPlanMs,
                    fillMs = last.fillMs,
                    slotPlanBacktracks = last.slotPlanBacktracks,
                    fillBacktracks = last.fillBacktracks,
                    fillRepoCalls = last.fillRepoCalls,
                    fillFirstSlotDomain = last.fillFirstSlotDomainSize,
                    shapeHash = shapeHash,
                    succeeded = outcome.succeeded,
                )
            if ((i + 1) % PROGRESS_INTERVAL == 0 || i == n - 1) {
                val successSoFar = results.count { it.succeeded }
                log.info(
                    "bench_fast_progress label={} done={} total={} elapsed_ms={} success={}",
                    label,
                    i + 1,
                    n,
                    System.currentTimeMillis() - loopStart,
                    successSoFar,
                )
            }
        }

        val successCount = results.count { it.succeeded }
        val unique = results.map { it.shapeHash }.distinct().size
        val collisions = n - unique
        log.info(
            "bench_fast_summary label={} width={} height={} n={} success={} timeouts={}",
            label,
            constraints.width,
            constraints.height,
            n,
            successCount,
            n - successCount,
        )
        logDistribution("total_ms", results.map { it.totalMs }.sorted())
        logDistribution("attempts_per_puzzle", results.map { it.attempts.toLong() }.sorted())
        logDistribution("skeleton_ms", results.map { it.skeletonMs }.sorted())
        logDistribution("slot_plan_ms", results.map { it.slotPlanMs }.sorted())
        logDistribution("fill_ms", results.map { it.fillMs }.sorted())
        log.info(
            "bench_shape_collisions label={} n={} unique={} collisions={}",
            label,
            n,
            unique,
            collisions,
        )

        val ts = System.currentTimeMillis()
        val outDir = Path.of("data/eval")
        Files.createDirectories(outDir)
        val outFile = outDir.resolve("grid_gen_bench_${label}_$ts.csv")
        outFile.bufferedWriter().use { w ->
            w.write(
                "seed,total_ms,attempts,skeleton_ms,slot_plan_ms,fill_ms," +
                    "slot_plan_backtracks,fill_backtracks,fill_repo_calls," +
                    "fill_first_slot_domain,shape_hash,succeeded\n",
            )
            results.forEach { r ->
                w.write(
                    "${r.seed},${r.totalMs},${r.attempts},${r.skeletonMs},${r.slotPlanMs}," +
                        "${r.fillMs},${r.slotPlanBacktracks},${r.fillBacktracks}," +
                        "${r.fillRepoCalls},${r.fillFirstSlotDomain},${r.shapeHash},${r.succeeded}\n",
                )
            }
        }
        log.info("bench_fast_csv_written label={} path={} rows={}", label, outFile, results.size)
    }
```

### Step 3 — Add the two new `@Test` methods

Inside `GridGenBenchmarkTest`, after the existing `200 puzzles via self-calibrating GeneratePuzzleUseCase` test and before the new `runBench` helper from Step 2, insert these two test methods:

```kotlin
    @Test
    fun `25 puzzles fast iteration bench`() {
        runBench(n = 25, label = "fast-25")
    }

    @Test
    fun `100 puzzles PR-gate bench`() {
        runBench(n = 100, label = "pr-gate")
    }
```

### Step 4 — Sanity-check the bench compiles and the 25-gen runs

Run:
```bash
./gradlew :grid:api:compileTestKotlin
```
Expected: BUILD SUCCESSFUL.

Then run the 25-gen variant once to confirm it executes end-to-end and writes a CSV:
```bash
./gradlew :grid:api:test --tests "*25 puzzles fast iteration bench*" -Dincludetags=bench
```

Expected:
- BUILD SUCCESSFUL.
- A new file at `data/eval/grid_gen_bench_fast-25_<timestamp>.csv` containing 26 lines (header + 25 rows).
- SLF4J log lines including `bench_fast_summary`, `bench_shape_collisions`, and `bench_fast_csv_written`.

If the test fails because the bench can't find `defaultPuzzleConstraints`, verify the existing `GridGenBenchmarkTest` already imports it (it must — it's used in the 200-gen tests). No new import needed if the file already had it.

### Step 5 — Run Spotless

Run:
```bash
./gradlew :grid:api:spotlessApply
```
Expected: BUILD SUCCESSFUL.

### Step 6 — Verify the 200-gen tests still compile (regression guard)

Run:
```bash
./gradlew :grid:api:compileTestKotlin
```
Expected: BUILD SUCCESSFUL. (Spotless may have reformatted; we re-compile to confirm nothing broke.)

### Step 7 — Commit

Run:
```bash
git add grid/api/src/test/kotlin/com/bliss/grid/api/GridGenBenchmarkTest.kt
git commit -m "$(cat <<'EOF'
test(grid): add 25/100-gen fast benches with shape-hash + attempts

Two new @Tag("bench") @Test methods that exercise GeneratePuzzleUseCase
via executeWithOutcome and capture per-attempt timings, GenerationMetrics
snapshots, and a structural shape hash per puzzle. CSV columns extended
with attempts and shape_hash; existing 200-gen tests are untouched.

The shared runBench helper seeds each iteration deterministically so
before/after comparisons across runs are reproducible.

Spec: docs/superpowers/specs/2026-05-11-grid-gen-wave1-observability-design.md
EOF
)"
```

Expected: one new commit, one file changed.

---

## Task 4 — `BenchDiff` helper + `compare baseline vs current` `@Test`

**Files:**
- Create: `grid/api/src/test/kotlin/com/bliss/grid/api/BenchDiff.kt`
- Create: `grid/api/src/test/kotlin/com/bliss/grid/api/BenchDiffTest.kt`
- Modify: `grid/api/src/test/kotlin/com/bliss/grid/api/GridGenBenchmarkTest.kt`

### Step 1 — Write the failing tests

Create `grid/api/src/test/kotlin/com/bliss/grid/api/BenchDiffTest.kt` with this exact content:

```kotlin
package com.bliss.grid.api

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BenchDiffTest {
    private val header =
        "seed,total_ms,attempts,skeleton_ms,slot_plan_ms,fill_ms," +
            "slot_plan_backtracks,fill_backtracks,fill_repo_calls," +
            "fill_first_slot_domain,shape_hash,succeeded"

    private fun writeCsv(
        dir: Path,
        name: String,
        rows: List<String>,
    ): Path {
        val p = dir.resolve(name)
        Files.writeString(p, (listOf(header) + rows).joinToString("\n") + "\n")
        return p
    }

    private fun row(
        seed: Long,
        totalMs: Long,
        attempts: Int = 1,
        skeletonMs: Long = 1,
        slotPlanMs: Long = 5,
        fillMs: Long = totalMs - 6,
        shapeHash: String = "h$seed",
        succeeded: Boolean = true,
    ): String =
        "$seed,$totalMs,$attempts,$skeletonMs,$slotPlanMs,$fillMs,0,0,0,-1,$shapeHash,$succeeded"

    @Test
    fun `identical CSVs produce zero deltas across every metric`(
        @TempDir dir: Path,
    ) {
        val rows = (1L..5L).map { row(it, totalMs = 100L * it) }
        val a = writeCsv(dir, "a.csv", rows)
        val b = writeCsv(dir, "b.csv", rows)
        val table = BenchDiff.compare(a, b)
        assertThat(table).contains("0%")
        assertThat(table).doesNotContain("▲")
        assertThat(table).doesNotContain("▼")
    }

    @Test
    fun `2x regression in total_ms p50 flags upward`(
        @TempDir dir: Path,
    ) {
        val baseline = (1L..10L).map { row(it, totalMs = 100L) }
        val current = (1L..10L).map { row(it, totalMs = 200L) }
        val table =
            BenchDiff.compare(
                writeCsv(dir, "base.csv", baseline),
                writeCsv(dir, "curr.csv", current),
            )
        assertThat(table).contains("total_ms p50")
        assertThat(table).contains("+100%")
        assertThat(table).contains("▲")
    }

    @Test
    fun `2x improvement in total_ms p50 flags downward`(
        @TempDir dir: Path,
    ) {
        val baseline = (1L..10L).map { row(it, totalMs = 200L) }
        val current = (1L..10L).map { row(it, totalMs = 100L) }
        val table =
            BenchDiff.compare(
                writeCsv(dir, "base.csv", baseline),
                writeCsv(dir, "curr.csv", current),
            )
        assertThat(table).contains("-50%")
        assertThat(table).contains("▼")
    }

    @Test
    fun `shape_collision_rate is reported`(
        @TempDir dir: Path,
    ) {
        // baseline: 10 unique hashes, current: 5 unique (heavy collisions)
        val baseline = (1L..10L).map { row(it, totalMs = 100L, shapeHash = "u$it") }
        val current = (1L..10L).map { row(it, totalMs = 100L, shapeHash = if (it <= 5) "c$it" else "c${it - 5}") }
        val table =
            BenchDiff.compare(
                writeCsv(dir, "base.csv", baseline),
                writeCsv(dir, "curr.csv", current),
            )
        assertThat(table).contains("shape_collision_rate")
    }

    @Test
    fun `success_rate is reported`(
        @TempDir dir: Path,
    ) {
        val baseline = (1L..10L).map { row(it, totalMs = 100L, succeeded = true) }
        val current =
            (1L..10L).map { row(it, totalMs = 100L, succeeded = it <= 8) } // 80% success
        val table =
            BenchDiff.compare(
                writeCsv(dir, "base.csv", baseline),
                writeCsv(dir, "curr.csv", current),
            )
        assertThat(table).contains("success_rate")
    }
}
```

### Step 2 — Run the tests, confirm compile failure

Run:
```bash
./gradlew :grid:api:compileTestKotlin
```
Expected: compilation error referencing `BenchDiff` (unresolved reference). Red phase.

### Step 3 — Implement `BenchDiff`

Create `grid/api/src/test/kotlin/com/bliss/grid/api/BenchDiff.kt` with this exact content:

```kotlin
package com.bliss.grid.api

import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads two bench CSVs (baseline + current) and produces a side-by-side
 * comparison table as a multi-line string. Flag column: `▼` for an
 * improvement of 10%+, `▲` for a regression of 10%+, blank otherwise.
 *
 * "Improvement" means "smaller is better" for latency / collision-rate
 * metrics and "larger is better" for success_rate. The flag direction is
 * inverted accordingly.
 *
 * Tolerant of missing columns: a metric absent from either side renders
 * as `n/a` and is not flagged.
 */
object BenchDiff {
    private const val FLAG_THRESHOLD = 0.10
    private const val COL_LABEL = 22
    private const val COL_NUM = 12
    private const val COL_DELTA = 9
    private const val COL_FLAG = 4

    fun compare(
        baseline: Path,
        current: Path,
    ): String {
        val a = readCsv(baseline)
        val b = readCsv(current)
        val sb = StringBuilder()
        sb.append(header()).append('\n')

        // Latency / count percentile metrics — smaller is better.
        addPercentileRow(sb, "total_ms p50", a, b, "total_ms", 50, smallerIsBetter = true)
        addPercentileRow(sb, "total_ms p95", a, b, "total_ms", 95, smallerIsBetter = true)
        addPercentileRow(sb, "total_ms p99", a, b, "total_ms", 99, smallerIsBetter = true)
        addPercentileRow(sb, "attempts_per_puzzle p50", a, b, "attempts", 50, smallerIsBetter = true)
        addPercentileRow(sb, "skeleton_ms p50", a, b, "skeleton_ms", 50, smallerIsBetter = true)
        addPercentileRow(sb, "slot_plan_ms p50", a, b, "slot_plan_ms", 50, smallerIsBetter = true)
        addPercentileRow(sb, "fill_ms p50", a, b, "fill_ms", 50, smallerIsBetter = true)

        // Rate-style summaries.
        addRateRow(sb, "shape_collision_rate", a, b, collisionRate(a), collisionRate(b), smallerIsBetter = true)
        addRateRow(sb, "success_rate", a, b, successRate(a), successRate(b), smallerIsBetter = false)

        return sb.toString()
    }

    private data class Csv(
        val header: List<String>,
        val rows: List<List<String>>,
    )

    private fun readCsv(path: Path): Csv {
        val lines = Files.readAllLines(path).filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "CSV is empty: $path" }
        val header = lines.first().split(",")
        val rows = lines.drop(1).map { it.split(",") }
        return Csv(header, rows)
    }

    private fun Csv.columnIndex(name: String): Int? {
        val i = header.indexOf(name)
        return if (i >= 0) i else null
    }

    private fun Csv.longsAt(column: String): List<Long>? {
        val i = columnIndex(column) ?: return null
        return rows.mapNotNull { it.getOrNull(i)?.toLongOrNull() }
    }

    private fun percentile(
        sorted: List<Long>,
        pct: Int,
    ): Long = sorted[(pct * (sorted.size - 1)) / 100]

    private fun collisionRate(csv: Csv): Double {
        val i = csv.columnIndex("shape_hash") ?: return Double.NaN
        val hashes = csv.rows.mapNotNull { it.getOrNull(i) }.filter { it.isNotBlank() }
        if (hashes.isEmpty()) return Double.NaN
        val unique = hashes.distinct().size
        return (hashes.size - unique).toDouble() / hashes.size
    }

    private fun successRate(csv: Csv): Double {
        val i = csv.columnIndex("succeeded") ?: return Double.NaN
        val flags = csv.rows.mapNotNull { it.getOrNull(i)?.toBooleanStrictOrNull() }
        if (flags.isEmpty()) return Double.NaN
        return flags.count { it }.toDouble() / flags.size
    }

    private fun addPercentileRow(
        sb: StringBuilder,
        label: String,
        a: Csv,
        b: Csv,
        column: String,
        pct: Int,
        smallerIsBetter: Boolean,
    ) {
        val aVals = a.longsAt(column)?.sorted()
        val bVals = b.longsAt(column)?.sorted()
        val baseVal = aVals?.takeIf { it.isNotEmpty() }?.let { percentile(it, pct) }
        val curVal = bVals?.takeIf { it.isNotEmpty() }?.let { percentile(it, pct) }
        appendRow(sb, label, baseVal?.toString(), curVal?.toString(), deltaPct(baseVal?.toDouble(), curVal?.toDouble()), smallerIsBetter)
    }

    private fun addRateRow(
        sb: StringBuilder,
        label: String,
        a: Csv,
        b: Csv,
        aRate: Double,
        bRate: Double,
        smallerIsBetter: Boolean,
    ) {
        val base = if (aRate.isNaN()) null else "%.3f".format(aRate)
        val cur = if (bRate.isNaN()) null else "%.3f".format(bRate)
        appendRow(sb, label, base, cur, deltaPct(if (aRate.isNaN()) null else aRate, if (bRate.isNaN()) null else bRate), smallerIsBetter)
    }

    private fun deltaPct(
        base: Double?,
        cur: Double?,
    ): Double? {
        if (base == null || cur == null) return null
        if (base == 0.0) return if (cur == 0.0) 0.0 else Double.POSITIVE_INFINITY
        return (cur - base) / base
    }

    private fun appendRow(
        sb: StringBuilder,
        label: String,
        base: String?,
        cur: String?,
        delta: Double?,
        smallerIsBetter: Boolean,
    ) {
        sb.append(label.padEnd(COL_LABEL))
        sb.append((base ?: "n/a").padEnd(COL_NUM))
        sb.append((cur ?: "n/a").padEnd(COL_NUM))
        val deltaStr = if (delta == null) "n/a" else "%+.0f%%".format(delta * 100)
        sb.append(deltaStr.padEnd(COL_DELTA))
        sb.append(flag(delta, smallerIsBetter).padEnd(COL_FLAG))
        sb.append('\n')
    }

    private fun flag(
        delta: Double?,
        smallerIsBetter: Boolean,
    ): String {
        if (delta == null || delta.isNaN() || !delta.isFinite()) return ""
        if (kotlin.math.abs(delta) < FLAG_THRESHOLD) return ""
        val regressed = if (smallerIsBetter) delta > 0 else delta < 0
        return if (regressed) "▲" else "▼"
    }

    private fun header(): String =
        "metric".padEnd(COL_LABEL) +
            "baseline".padEnd(COL_NUM) +
            "current".padEnd(COL_NUM) +
            "delta".padEnd(COL_DELTA) +
            "flag".padEnd(COL_FLAG)
}
```

### Step 4 — Run the tests, confirm green

Run:
```bash
./gradlew :grid:api:test --tests "com.bliss.grid.api.BenchDiffTest"
```
Expected: 5 tests pass. BUILD SUCCESSFUL.

### Step 5 — Add the `compare baseline vs current` `@Test`

Open `grid/api/src/test/kotlin/com/bliss/grid/api/GridGenBenchmarkTest.kt`. Add this import alongside the existing imports:

```kotlin
import org.junit.jupiter.api.Assumptions
```

Inside the `GridGenBenchmarkTest` class, after the `100 puzzles PR-gate bench` `@Test` and before the `private fun runBench` helper, insert:

```kotlin
    /**
     * Reads two CSVs produced by [runBench] (or the 200-gen tests) and logs a
     * side-by-side diff table. Skipped unless both `-Dbench.baseline=...` and
     * `-Dbench.current=...` are supplied on the command line. Carries the
     * `@Tag("bench")` so it stays out of the default CI lane.
     */
    @Test
    fun `compare baseline vs current`() {
        val baseline = System.getProperty("bench.baseline")
        val current = System.getProperty("bench.current")
        Assumptions.assumeTrue(
            baseline != null && current != null,
            "supply -Dbench.baseline=<path> -Dbench.current=<path> to run this diff",
        )
        val table = BenchDiff.compare(Path.of(baseline), Path.of(current))
        log.info("bench_diff_table\n{}", table)
    }
```

### Step 6 — Confirm the diff test is discovered (assume-skipped by default)

Run:
```bash
./gradlew :grid:api:test --tests "*compare baseline vs current*" -Dincludetags=bench
```
Expected: BUILD SUCCESSFUL, test reported as skipped (not failed) because no properties are set. The skip reason is logged.

### Step 7 — Run Spotless

Run:
```bash
./gradlew :grid:api:spotlessApply
```
Expected: BUILD SUCCESSFUL.

### Step 8 — Commit

Run:
```bash
git add grid/api/src/test/kotlin/com/bliss/grid/api/BenchDiff.kt \
        grid/api/src/test/kotlin/com/bliss/grid/api/BenchDiffTest.kt \
        grid/api/src/test/kotlin/com/bliss/grid/api/GridGenBenchmarkTest.kt
git commit -m "$(cat <<'EOF'
test(grid): add BenchDiff comparator for baseline/current bench CSVs

BenchDiff.compare(baseline, current) reads two grid-gen-bench CSVs and
produces a side-by-side table (total_ms percentiles, per-phase split,
attempts-per-puzzle, shape-collision-rate, success-rate) with ▲/▼ flags
on 10%+ deltas. Exposed via a third @Tag("bench") @Test that reads
-Dbench.baseline / -Dbench.current and logs the table; skipped when
either property is missing.

Spec: docs/superpowers/specs/2026-05-11-grid-gen-wave1-observability-design.md
EOF
)"
```

Expected: one new commit, three files changed.

---

## Task 5 — `.gitignore` patterns for bench CSVs

**Files:**
- Modify: `.gitignore`

### Step 1 — Read the existing `.gitignore`

Open `.gitignore` and locate the existing `data/eval/*` patterns (they're grouped — `lemma_clues.csv`, `lemma_clues.mistral-nemo.csv`, etc.).

### Step 2 — Append the new patterns

Add these three lines at the end of the existing `data/eval/` block (or anywhere clearly grouped with the other `data/eval/` patterns):

```
data/eval/grid_gen_bench_*.csv
data/eval/baseline_wave-*.csv
data/eval/current_wave-*.csv
```

### Step 3 — Verify CSVs from Task 3's run are now ignored

Run:
```bash
git status --short data/eval/
```
Expected: no `??` line for `data/eval/grid_gen_bench_*.csv` files. If any such files were committed previously, the new pattern won't untrack them — but the prior Task 3 run wrote a fresh CSV that should now be ignored.

If the CSV is still untracked-and-visible, the pattern isn't matching. Check that the line was added exactly as written.

### Step 4 — Commit

Run:
```bash
git add .gitignore
git commit -m "$(cat <<'EOF'
chore(grid): ignore bench CSV artefacts

Three patterns for grid-gen benchmark outputs:
- grid_gen_bench_*.csv (raw bench runs)
- baseline_wave-*.csv (per-wave baseline snapshots)
- current_wave-*.csv (per-wave current snapshots)

Bench CSVs are machine-specific dev artefacts and should not be
committed.

Spec: docs/superpowers/specs/2026-05-11-grid-gen-wave1-observability-design.md
EOF
)"
```

Expected: one new commit, one file changed.

---

## Verification (no commit — exit-criteria check)

### Step 1 — Run the full grid-module build

Run:
```bash
./gradlew :grid:domain:build :grid:application:build :grid:api:build
```
Expected: BUILD SUCCESSFUL across all three modules. Konsist arch tests pass. Spotless clean.

### Step 2 — Capture the 100-gen baseline from `main`

```bash
git checkout main
./gradlew :grid:api:test --tests "*100 puzzles PR-gate bench*" -Dincludetags=bench
# CSV lands at data/eval/grid_gen_bench_pr-gate_<ts>.csv — copy it aside.
cp data/eval/grid_gen_bench_pr-gate_*.csv /tmp/baseline_wave-1.csv
```

Expected: BUILD SUCCESSFUL, one CSV with 100 rows.

Note: **on `main` the `runBench` helper and the `100 puzzles PR-gate bench` test do not exist yet** — they were introduced in Task 3 of this very plan. To produce a baseline from `main` you have two options:

1. **Use the existing 200-gen `via self-calibrating GeneratePuzzleUseCase` test on `main`**, then accept that it doesn't capture `attempts` or `shape_hash`. `BenchDiff.compare` will render those rows as `n/a`. Sufficient for verifying "Wave 1 didn't change algorithm behaviour" — total_ms p50/p95/p99 are the load-bearing exit-criterion numbers.
2. **Cherry-pick Task 3's commit onto `main` in a throwaway worktree**, run the new 100-gen bench there, then discard. Cleaner numbers but more steps.

For Wave 1's verification we use option 1. From `main`:

```bash
git checkout main
./gradlew :grid:api:test --tests "*200 puzzles via self-calibrating*" -Dincludetags=bench
cp data/eval/grid_gen_bench_*.csv /tmp/baseline_wave-1.csv
```

### Step 3 — Capture the 100-gen current from the Wave 1 tip

```bash
git checkout feat/grid-gen-enhance
./gradlew :grid:api:test --tests "*100 puzzles PR-gate bench*" -Dincludetags=bench
cp data/eval/grid_gen_bench_pr-gate_*.csv /tmp/current_wave-1.csv
```

Expected: BUILD SUCCESSFUL, fresh CSV.

### Step 4 — Run the diff

```bash
./gradlew :grid:api:test \
  --tests "*compare baseline vs current*" \
  -Dincludetags=bench \
  -Dbench.baseline=/tmp/baseline_wave-1.csv \
  -Dbench.current=/tmp/current_wave-1.csv
```

Expected: BUILD SUCCESSFUL. The logs include `bench_diff_table` followed by the side-by-side table.

### Step 5 — Verify the exit criterion

Read the table. Expected outcome:
- **`total_ms p50`** delta within ±10% (no algorithm change, only retry-loop accounting). Blank flag, or ▼/▲ only from natural variance.
- **`success_rate`** within ±5% (no behaviour change).
- **`attempts_per_puzzle p50`** rendered as `n/a` for baseline (the 200-gen test didn't record attempts), populated for current. Not a regression.
- **`shape_collision_rate`** rendered as `n/a` for baseline (no shape-hash column on `main`), populated for current. Establishes the Wave 1 baseline for later waves to compare against.

If `total_ms p50` shows a `▲` flag (10%+ regression), investigate before declaring Wave 1 done — Wave 1 must not change algorithm behaviour.

### Step 6 — Done

Wave 1 is complete when:
- All five commits are on `feat/grid-gen-enhance`.
- `./gradlew :grid:domain:test :grid:application:test :grid:api:build` is green.
- The diff table from Step 5 shows no `▲` flag on `total_ms` percentiles.

The PR description (whenever the branch is sliced for merge) carries the table from Step 5 inline.

---

## Out of scope (do not implement)

- Production observability (OTel, structured logs of attempt counts in prod). Later wave.
- Per-phase budget *policy* (enforcing "planner gets 30% of deadline"). Later wave.
- Word-level diversity metric (word-collision rate across grids). After Wave 3.
- Adding or renaming fields in `GenerationMetrics`. Consume what's there.
- Algorithm changes of any kind. Wave 1 is observation-only.
- Replacing or removing the existing 200-gen `@Test` methods.

These are explicitly deferred. If a need arises during implementation, surface it as a follow-up issue, not an in-flight scope creep.
