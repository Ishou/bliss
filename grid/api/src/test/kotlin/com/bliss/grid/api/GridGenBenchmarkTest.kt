package com.bliss.grid.api

import com.bliss.grid.application.puzzle.defaultPuzzleConstraints
import com.bliss.grid.domain.generation.GenerationMetrics
import com.bliss.grid.domain.generation.GridGenerator
import com.bliss.grid.infrastructure.persistence.CsvWordRepository
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.bufferedWriter
import kotlin.random.Random

/**
 * Per-phase grid-generation benchmark. Generates N puzzles with deterministic
 * seeds, captures [GenerationMetrics] from each run, and prints/saves the
 * distribution. Runs opt-in via the `bench` JUnit tag — not part of CI.
 *
 * Usage: `./gradlew :grid:api:test -Dincludetags=bench`
 *   or:  `./gradlew :grid:api:test --tests '*GridGenBenchmarkTest*'`
 *
 * Output goes to:
 *   - stdout (summary stats: median / p95 / p99 per phase)
 *   - data/eval/grid_gen_bench_<timestamp>.csv (one row per attempt)
 */
@Tag("bench")
class GridGenBenchmarkTest {
    @Test
    fun `200 puzzles per-phase metrics on the production corpus`() {
        val repo = CsvWordRepository.frenchFromClasspath()
        val generator = GridGenerator(repo)
        val constraints = defaultPuzzleConstraints()

        // JIT warm-up — first few generations recompile hot paths and aren't
        // representative of steady-state perf.
        repeat(5) { generator.generate(constraints, Random(it.toLong())) }

        val n = 200
        val results = ArrayList<GenerationMetrics>(n)
        for (i in 0 until n) {
            val m = GenerationMetrics()
            generator.generate(constraints, Random(i.toLong() * 1000), metrics = m)
            results += m
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

        fun List<Long>.p(pct: Int) = this[(pct * (size - 1)) / 100]
        fun List<Long>.fmt() = "min=${first()} p50=${p(50)} p75=${p(75)} p95=${p(95)} p99=${p(99)} max=${last()}"

        println(
            """
            |=== Grid-gen benchmark: $n puzzles (${constraints.width}x${constraints.height}, default constraints) ===
            |success: $successCount/$n  (${"%.1f".format(100.0 * successCount / n)}%)
            |timeouts: $timeoutCount
            |
            |Per-phase wall time (ms):
            |  total:     ${totalMs.fmt()}
            |  skeleton:  ${skeletonMs.fmt()}
            |  slot-plan: ${slotPlanMs.fmt()}
            |  fill:      ${fillMs.fmt()}
            |
            |Search effort:
            |  slot-plan backtracks: ${planBacks.fmt()}
            |  fill backtracks:      ${fillBacks.fmt()}
            |  fill repo calls:      ${repoCalls.fmt()}
            |  first-slot domain:    ${firstSlotDom.fmt()}
            |
            """.trimMargin(),
        )

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
        println("[bench] CSV: $outFile (${results.size} rows)")
    }
}
