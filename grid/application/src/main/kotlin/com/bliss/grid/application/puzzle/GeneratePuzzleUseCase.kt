package com.bliss.grid.application.puzzle

import com.bliss.grid.domain.generation.GridConstraints
import com.bliss.grid.domain.generation.GridGenerator
import com.bliss.grid.domain.generation.WordRepository
import com.bliss.grid.domain.model.Grid
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * Generates a fresh interlocked grid for a single puzzle request.
 *
 * Calls the domain [GridGenerator] up to [maxAttempts] times — each attempt uses
 * a fresh [Random] so refreshes vary. Returns the first successful grid, or
 * `null` if every attempt fails (the API adapter then surfaces 422).
 *
 * The [defaults] constraints govern requests that omit `width`/`height`.
 * Per the multiplayer rollout, lobby owners may request a non-default size
 * via [execute]; range validation lives at the API edge so the use case
 * trusts whatever the caller passes through.
 */
class GeneratePuzzleUseCase(
    wordRepository: WordRepository,
    private val defaults: GridConstraints,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    private val log = LoggerFactory.getLogger(GeneratePuzzleUseCase::class.java)
    private val generator = GridGenerator(wordRepository)

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
            generator.generate(constraints, random)?.let { return it }
            log.warn(
                "puzzle_generation_retry attempt={} width={} height={}",
                attempt + 1,
                constraints.width,
                constraints.height,
            )
        }
        return null
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 5
    }
}
