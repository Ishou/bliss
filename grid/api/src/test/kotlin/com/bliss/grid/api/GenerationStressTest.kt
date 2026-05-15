package com.bliss.grid.api

import com.bliss.grid.application.puzzle.defaultPuzzleConstraints
import com.bliss.grid.domain.generation.GridGenerator
import com.bliss.grid.infrastructure.persistence.CsvWordRepository
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
    fun `30 puzzles end-to-end on the production corpus`() {
        val repo = CsvWordRepository.frenchFromClasspath()
        val generator = GridGenerator(repo)
        val constraints = defaultPuzzleConstraints()
        val sampleSize = 30

        // Warm-up the JIT.
        repeat(3) { generator.generate(constraints, Random(it.toLong())) }

        val timings = LongArray(sampleSize)
        var successCount = 0
        for (i in 0 until sampleSize) {
            val ms =
                measureTimeMillis {
                    val grid = generator.generate(constraints, Random(i.toLong() * 1000))
                    if (grid != null) successCount++
                }
            timings[i] = ms
        }
        timings.sort()
        val p50 = timings[sampleSize / 2]
        val p95 = timings[(sampleSize * 95 / 100).coerceAtMost(sampleSize - 1)]
        val min = timings[0]
        val max = timings[sampleSize - 1]
        // Single-attempt success rate is loose because GeneratePuzzleUseCase's
        // outer retry loop covers single-attempt failures — an API-level
        // success rate of ~100 % is typical even when direct
        // GridGenerator.generate single-attempt is in the 35–50 % band at
        // the default 15×12 dimensions. The useful productivity bar is
        // per-attempt wall time (failed attempts must abandon fast so
        // retries can amortise the budget), not single-attempt success.
        check(p50 < 3_000) {
            "median per-attempt latency ${p50}ms exceeds 3s budget " +
                "(success=$successCount/$sampleSize min=${min}ms p95=${p95}ms max=${max}ms)"
        }
        check(successCount >= sampleSize * 3 / 10) {
            "only $successCount/$sampleSize single-attempt generations succeeded " +
                "(min=${min}ms p50=${p50}ms p95=${p95}ms max=${max}ms)"
        }
    }
}
