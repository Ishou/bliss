package com.bliss.grid.application.puzzle

import com.bliss.grid.domain.generation.DEFAULT_GENERATION_TIMEOUT_MS
import com.bliss.grid.domain.generation.GridConstraints
import com.bliss.grid.domain.generation.GridGenerator
import com.bliss.grid.domain.generation.WordRepository
import com.bliss.grid.domain.model.Grid
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * Generates a fresh interlocked grid for a single puzzle request.
 *
 * Strategy: early-abandon + retry with a *self-calibrating* per-attempt
 * timeout. Most successful attempts complete in O(median) wall time on
 * the host machine; pathological puzzles (~15% on the production corpus)
 * run for orders of magnitude longer with no payoff. Setting the
 * per-attempt timeout to a multiple of the recent successful-attempt
 * median lets the latter abandon quickly and retry with a fresh seed,
 * which usually succeeds in O(median) again. Net wall time for the
 * worst-case user request drops from 5s+ to ~10× median.
 *
 * The cutoff adapts to host capacity automatically — a slow CI runner's
 * median is high, so its cutoff is high in absolute ms, but the same
 * ratio. No tunable that needs per-machine adjustment.
 *
 * Cold start (no successes recorded yet): uses
 * [DEFAULT_GENERATION_TIMEOUT_MS] (5s) for the first few attempts. Once
 * [warmupCount] successes are captured, switches to the calibrated
 * cutoff = [cutoffMultiplier] × observed median, clamped to
 * [[minAttemptTimeoutMs], [maxAttemptTimeoutMs]].
 */
class GeneratePuzzleUseCase(
    wordRepository: WordRepository,
    private val defaults: GridConstraints,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val cutoffMultiplier: Int = 10,
    private val minAttemptTimeoutMs: Long = 200,
    private val maxAttemptTimeoutMs: Long = DEFAULT_GENERATION_TIMEOUT_MS,
    private val warmupCount: Int = 3,
    private val rollingWindow: Int = 20,
) {
    private val log = LoggerFactory.getLogger(GeneratePuzzleUseCase::class.java)
    private val generator = GridGenerator(wordRepository)

    /**
     * Recent successful attempt wall times (ms). Kept synchronized — callers
     * can run [execute] concurrently from a Ktor route, and the calibration
     * window is shared across requests so successful generations from any
     * thread inform every thread's cutoff.
     */
    private val recentSuccessMs = ArrayDeque<Long>()

    fun execute(
        width: Int? = null,
        height: Int? = null,
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
            val grid = generator.generate(constraints, random, timeoutMs = timeoutMs)
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

    private fun perAttemptTimeoutMs(): Long {
        // Snapshot the rolling window under lock; compute median outside.
        val sample =
            synchronized(recentSuccessMs) {
                if (recentSuccessMs.size < warmupCount) return maxAttemptTimeoutMs
                recentSuccessMs.toLongArray()
            }
        sample.sort()
        val median = sample[sample.size / 2]
        return (median * cutoffMultiplier).coerceIn(minAttemptTimeoutMs, maxAttemptTimeoutMs)
    }

    private fun recordSuccess(elapsedMs: Long) {
        synchronized(recentSuccessMs) {
            recentSuccessMs.addLast(elapsedMs)
            while (recentSuccessMs.size > rollingWindow) recentSuccessMs.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 10
    }
}
