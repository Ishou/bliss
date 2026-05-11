package com.bliss.grid.api

import com.bliss.grid.application.puzzle.GeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.defaultPuzzleConstraints
import com.bliss.grid.domain.generation.GenerationMetrics
import com.bliss.grid.domain.generation.GridGenerator
import com.bliss.grid.domain.generation.GridShapeHash
import com.bliss.grid.infrastructure.persistence.CsvWordRepository
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.bufferedWriter
import kotlin.random.Random

private const val WARMUP_N = 5
private const val PROGRESS_INTERVAL = 20

/**
 * Per-phase grid-generation benchmark. Generates N puzzles with deterministic
 * seeds, captures [GenerationMetrics] from each run, and emits structured
 * SLF4J logs (parsed into JSON by the production logback config). Runs opt-in
 * via the `bench` JUnit tag — not part of CI.
 *
 * Usage: `./gradlew :grid:api:test -Dincludetags=bench`
 *   or:  `./gradlew :grid:api:test --tests '*GridGenBenchmarkTest*'`
 *
 * Per-puzzle metrics are also dumped to
 * `data/eval/grid_gen_bench_<timestamp>.csv` for later analysis.
 */
@Tag("bench")
class GridGenBenchmarkTest {
    private val log = LoggerFactory.getLogger(GridGenBenchmarkTest::class.java)

    @Test
    fun `200 puzzles per-phase metrics on the production corpus`() {
        val repo = CsvWordRepository.frenchFromClasspath()
        val generator = GridGenerator(repo)
        val constraints = defaultPuzzleConstraints()

        // JIT warm-up — first few generations recompile hot paths and aren't
        // representative of steady-state perf.
        log.info(
            "bench_warmup_start width={} height={} warmup_n={}",
            constraints.width,
            constraints.height,
            WARMUP_N,
        )
        val warmStart = System.currentTimeMillis()
        repeat(WARMUP_N) { generator.generate(constraints, Random(it.toLong())) }
        log.info("bench_warmup_done elapsed_ms={}", System.currentTimeMillis() - warmStart)

        val n = 200
        log.info("bench_loop_start n={}", n)
        val results = ArrayList<GenerationMetrics>(n)
        val loopStart = System.currentTimeMillis()
        for (i in 0 until n) {
            val m = GenerationMetrics()
            generator.generate(constraints, Random(i.toLong() * 1000), metrics = m)
            results += m
            if ((i + 1) % PROGRESS_INTERVAL == 0 || i == n - 1) {
                val successSoFar = results.count { it.succeeded }
                log.info(
                    "bench_progress done={} total={} elapsed_ms={} success={}",
                    i + 1,
                    n,
                    System.currentTimeMillis() - loopStart,
                    successSoFar,
                )
            }
        }

        // ---- Summary stats ----
        val totalMs = results.map { it.skeletonMs + it.slotPlanMs + it.fillMs }.sorted()
        val skeletonMs = results.map { it.skeletonMs }.sorted()
        val slotPlanMs = results.map { it.slotPlanMs }.sorted()
        val fillMs = results.map { it.fillMs }.sorted()
        val planBacks = results.map { it.slotPlanBacktracks.toLong() }.sorted()
        val fillBacks = results.map { it.fillBacktracks.toLong() }.sorted()
        val repoCalls = results.map { it.fillRepoCalls.toLong() }.sorted()
        val firstSlotDom = results.map { it.fillFirstSlotDomainSize.toLong() }.sorted()
        val successCount = results.count { it.succeeded }
        val timeoutCount = n - successCount

        log.info(
            "bench_summary width={} height={} n={} success={} timeouts={}",
            constraints.width,
            constraints.height,
            n,
            successCount,
            timeoutCount,
        )
        logDistribution("total_ms", totalMs)
        logDistribution("skeleton_ms", skeletonMs)
        logDistribution("slot_plan_ms", slotPlanMs)
        logDistribution("fill_ms", fillMs)
        logDistribution("slot_plan_backtracks", planBacks)
        logDistribution("fill_backtracks", fillBacks)
        logDistribution("fill_repo_calls", repoCalls)
        logDistribution("first_slot_domain", firstSlotDom)

        // ---- CSV dump ----
        val ts = System.currentTimeMillis()
        val outDir = Path.of("data/eval")
        Files.createDirectories(outDir)
        val outFile = outDir.resolve("grid_gen_bench_$ts.csv")
        outFile.bufferedWriter().use { w ->
            w.write(
                "seed,total_ms,skeleton_ms,slot_plan_ms,slot_plan_backtracks," +
                    "fill_ms,fill_backtracks,fill_repo_calls,fill_first_slot_domain,succeeded\n",
            )
            results.forEachIndexed { i, m ->
                val seed = i.toLong() * 1000
                val total = m.skeletonMs + m.slotPlanMs + m.fillMs
                w.write(
                    "$seed,$total,${m.skeletonMs},${m.slotPlanMs},${m.slotPlanBacktracks}," +
                        "${m.fillMs},${m.fillBacktracks},${m.fillRepoCalls},${m.fillFirstSlotDomainSize},${m.succeeded}\n",
                )
            }
        }
        log.info("bench_csv_written path={} rows={}", outFile, results.size)
    }

    private fun logDistribution(
        name: String,
        sorted: List<Long>,
    ) {
        fun List<Long>.p(pct: Int) = this[(pct * (size - 1)) / 100]
        log.info(
            "bench_dist name={} min={} p50={} p75={} p95={} p99={} max={}",
            name,
            sorted.first(),
            sorted.p(50),
            sorted.p(75),
            sorted.p(95),
            sorted.p(99),
            sorted.last(),
        )
    }

    /**
     * Same 200-puzzle eval but routed through [GeneratePuzzleUseCase],
     * which has a self-calibrating per-attempt timeout (= 10× recent
     * successful-attempt median, clamped 200ms..5000ms) and retry up to
     * maxAttempts. Cold start uses the full 5s timeout; once a few
     * successes are captured the cutoff tightens automatically.
     *
     * The "total wall time per puzzle" is the production-relevant metric:
     * what the user sees when they hit refresh. We measure it without any
     * tunable that needs per-machine setting — the strategy adapts.
     */
    @Test
    fun `200 puzzles via self-calibrating GeneratePuzzleUseCase`() {
        val repo = CsvWordRepository.frenchFromClasspath()
        val constraints = defaultPuzzleConstraints()
        val useCase = GeneratePuzzleUseCase(repo, constraints)

        log.info("bench_usecase_warmup_start warmup_n={}", WARMUP_N)
        val warmStart = System.currentTimeMillis()
        repeat(WARMUP_N) { useCase.execute() }
        log.info("bench_usecase_warmup_done elapsed_ms={}", System.currentTimeMillis() - warmStart)

        val n = 200
        log.info("bench_usecase_loop_start n={}", n)
        val totalMs = LongArray(n)
        var successCount = 0
        val loopStart = System.currentTimeMillis()
        for (i in 0 until n) {
            val start = System.currentTimeMillis()
            val grid = useCase.execute()
            totalMs[i] = System.currentTimeMillis() - start
            if (grid != null) successCount++
            if ((i + 1) % PROGRESS_INTERVAL == 0 || i == n - 1) {
                log.info(
                    "bench_usecase_progress done={} total={} elapsed_ms={} success={}",
                    i + 1,
                    n,
                    System.currentTimeMillis() - loopStart,
                    successCount,
                )
            }
        }
        val sorted = totalMs.sortedArray().toList()

        log.info("bench_usecase_summary n={} success={}", n, successCount)
        logDistribution("usecase_total_ms", sorted)
    }

    @Test
    fun `25 puzzles fast iteration bench`() {
        runBench(n = 25, label = "fast-25")
    }

    @Test
    fun `100 puzzles PR-gate bench`() {
        runBench(n = 100, label = "pr-gate")
    }

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

    /**
     * Shared fast-bench loop used by the 25-gen and 100-gen `@Test` methods.
     *
     * Uses the direct [GridGenerator] (single attempt per seed, no retries).
     * Deterministic — same seeds across runs produce the same outputs, so
     * before/after comparisons are signal, not noise. Cells where the seed
     * fails record `succeeded=false` and `shape_hash="FAIL"`; the bench
     * moves on.
     *
     * Trade-off: this measures *algorithm quality* (per-seed wall time, per-
     * phase split, first-attempt success rate, shape diversity) not user
     * experience (retry-loop wall time). The existing 200-gen useCase test
     * remains the right tool for the latter.
     */
    private fun runBench(
        n: Int,
        label: String,
    ) {
        val repo = CsvWordRepository.frenchFromClasspath()
        val generator = GridGenerator(repo)
        val constraints = defaultPuzzleConstraints()

        log.info("bench_fast_warmup_start label={} warmup_n={}", label, WARMUP_N)
        val warmStart = System.currentTimeMillis()
        repeat(WARMUP_N) { i -> generator.generate(constraints, Random(i.toLong())) }
        log.info("bench_fast_warmup_done label={} elapsed_ms={}", label, System.currentTimeMillis() - warmStart)

        data class Row(
            val seed: Long,
            val totalMs: Long,
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
            val m = GenerationMetrics()
            val start = System.currentTimeMillis()
            val grid = generator.generate(constraints, Random(seed), metrics = m)
            val elapsed = System.currentTimeMillis() - start
            val shapeHash = if (grid != null) GridShapeHash.of(grid) else "FAIL"
            results +=
                Row(
                    seed = seed,
                    totalMs = elapsed,
                    skeletonMs = m.skeletonMs,
                    slotPlanMs = m.slotPlanMs,
                    fillMs = m.fillMs,
                    slotPlanBacktracks = m.slotPlanBacktracks,
                    fillBacktracks = m.fillBacktracks,
                    fillRepoCalls = m.fillRepoCalls,
                    fillFirstSlotDomain = m.fillFirstSlotDomainSize,
                    shapeHash = shapeHash,
                    succeeded = m.succeeded,
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
                "seed,total_ms,skeleton_ms,slot_plan_ms,fill_ms," +
                    "slot_plan_backtracks,fill_backtracks,fill_repo_calls," +
                    "fill_first_slot_domain,shape_hash,succeeded\n",
            )
            results.forEach { r ->
                w.write(
                    "${r.seed},${r.totalMs},${r.skeletonMs},${r.slotPlanMs}," +
                        "${r.fillMs},${r.slotPlanBacktracks},${r.fillBacktracks}," +
                        "${r.fillRepoCalls},${r.fillFirstSlotDomain},${r.shapeHash},${r.succeeded}\n",
                )
            }
        }
        log.info("bench_fast_csv_written label={} path={} rows={}", label, outFile, results.size)
    }
}
