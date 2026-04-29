package com.bliss.grid.api

import com.bliss.grid.api.infrastructure.words.CsvWordRepository
import com.bliss.grid.api.routes.defaultConstraints
import com.bliss.grid.domain.generation.GridGenerator
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * Stress test for puzzle generation latency. Runs N generations back-to-back on a
 * single warm repository, reports min/median/p95/max wall-clock per generation.
 *
 * Tagged "stress" so CI can opt-in. Run with `--tests "*StressTest*"` locally.
 */
@Tag("stress")
class GenerationStressTest {
    @Test
    fun `100 puzzles end-to-end on the production corpus`() {
        val repo = CsvWordRepository.frenchFromClasspath()
        val generator = GridGenerator(repo)
        val constraints = defaultConstraints()

        // Warm-up the JIT.
        repeat(3) { generator.generate(constraints, Random(it.toLong())) }

        val timings = LongArray(100)
        var successCount = 0
        for (i in 0 until 100) {
            val ms =
                measureTimeMillis {
                    val grid = generator.generate(constraints, Random(i.toLong() * 1000))
                    if (grid != null) successCount++
                }
            timings[i] = ms
        }
        timings.sort()
        val p50 = timings[50]
        val p95 = timings[95]
        val min = timings[0]
        val max = timings[99]
        // Single-attempt success rate is loose because the API's outer retry loop
        // (MAX_OUTER_RETRIES = 5 in PuzzleRoute.kt) covers single-attempt failures —
        // an API-level success rate of 99.99%+ is typical even with single-attempt at 60%.
        // Ratchet the threshold down only if these regress.
        check(p50 < 3_000) {
            "median latency ${p50}ms exceeds 3s budget " +
                "(success=$successCount/100 min=${min}ms p95=${p95}ms max=${max}ms)"
        }
        check(successCount >= 60) {
            "only $successCount/100 single-attempt generations succeeded " +
                "(min=${min}ms p50=${p50}ms p95=${p95}ms max=${max}ms)"
        }
    }
}
