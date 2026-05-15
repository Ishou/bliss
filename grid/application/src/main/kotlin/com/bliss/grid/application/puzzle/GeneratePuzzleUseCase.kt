package com.bliss.grid.application.puzzle

import com.bliss.grid.domain.generation.Clock
import com.bliss.grid.domain.generation.ClueCooldownPolicy
import com.bliss.grid.domain.generation.GenerationMetrics
import com.bliss.grid.domain.generation.GridConstraints
import com.bliss.grid.domain.generation.GridGenerator
import com.bliss.grid.domain.generation.SystemClock
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
 * [DEFAULT_MAX_ATTEMPT_TIMEOUT_MS] for the first few attempts. Once
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
    private val maxAttemptTimeoutMs: Long = DEFAULT_MAX_ATTEMPT_TIMEOUT_MS,
    private val warmupCount: Int = 3,
    private val rollingWindow: Int = 20,
    private val clock: Clock = SystemClock,
) {
    private val log = LoggerFactory.getLogger(GeneratePuzzleUseCase::class.java)
    private val generator = GridGenerator(wordRepository, clock)

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
        cooldownPolicy: ClueCooldownPolicy = ClueCooldownPolicy.Inert,
    ): Grid? = executeWithOutcome(width, height, cooldownPolicy).grid

    /**
     * Run the retry loop and return the full [AttemptOutcome] — attempts count,
     * per-attempt wall time, per-attempt metrics, total time.
     *
     * [randomFactory] controls how each retry's `Random` is seeded. The default
     * uses the injected [clock]'s nanos so retries get well-spread seeds in
     * production; bench tests override this for deterministic, reproducible runs.
     */
    fun executeWithOutcome(
        width: Int? = null,
        height: Int? = null,
        cooldownPolicy: ClueCooldownPolicy = ClueCooldownPolicy.Inert,
        randomFactory: (attempt: Int) -> Random = { Random(clock.nanoTime() + it) },
        attemptsOverride: Int? = null,
        perAttemptTimeoutMsOverride: Long? = null,
    ): AttemptOutcome {
        val constraints =
            defaults.copy(
                width = width ?: defaults.width,
                height = height ?: defaults.height,
            )
        val effectiveAttempts = attemptsOverride ?: maxAttempts
        val perAttemptMs = ArrayList<Long>(effectiveAttempts)
        val perAttemptMetrics = ArrayList<GenerationMetrics>(effectiveAttempts)
        repeat(effectiveAttempts) { attempt ->
            val random = randomFactory(attempt)
            val timeoutMs = perAttemptTimeoutMsOverride ?: perAttemptTimeoutMs()
            val started = clock.currentTimeMillis()
            val metrics = GenerationMetrics()
            val grid =
                generator.generate(
                    constraints,
                    random,
                    metrics = metrics,
                    timeoutMs = timeoutMs,
                    cooldownPolicy = cooldownPolicy,
                )
            val elapsed = clock.currentTimeMillis() - started
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
            attempts = effectiveAttempts,
            perAttemptMs = perAttemptMs.toList(),
            perAttemptMetrics = perAttemptMetrics.toList(),
            totalMs = perAttemptMs.sum(),
        )
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
        /**
         * Default retry cap. With the shipped layout defaults
         * ([GenerationKnobs.DEFAULT_BLACK_RATIO] = 0.14, no bias) measured
         * avg attempts-to-success is ~3, so 10 leaves ample headroom.
         * Callers opting into [GridConstraints.longWordBias] should pass
         * a higher value (`50`) to absorb the longer tail.
         */
        const val DEFAULT_MAX_ATTEMPTS: Int = 10

        /**
         * Default per-attempt deadline. Single-attempt p90 ≈ 4.5s under
         * the shipped defaults; 5s is the right ceiling for a Luby
         * attempt to either land or give up so the next retry can start
         * with a fresh seed.
         */
        const val DEFAULT_MAX_ATTEMPT_TIMEOUT_MS: Long = 5_000L
    }
}
