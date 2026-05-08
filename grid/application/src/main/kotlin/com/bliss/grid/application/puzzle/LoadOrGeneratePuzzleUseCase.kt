package com.bliss.grid.application.puzzle

import java.time.Clock
import java.util.UUID

/**
 * Composes [PuzzleRepository] with the existing [GeneratePuzzleUseCase] so
 * GET `/v1/puzzles/{id}` becomes idempotent: the first call generates +
 * stores; subsequent calls (validate, hints, refresh) return the same grid.
 *
 * Decoupling the wire id from the generator implementation matters for
 * forward-compat: any future change to corpus / CSP heuristics / Random
 * stdlib would silently invalidate existing ids if we re-derived from a
 * seed. Storing the placement list keeps live puzzles stable across
 * generator code churn.
 */
class LoadOrGeneratePuzzleUseCase(
    private val puzzleRepository: PuzzleRepository,
    private val generatePuzzle: GeneratePuzzleUseCase,
    private val clock: Clock = Clock.systemUTC(),
    private val hintsAllowed: Int = DEFAULT_HINTS_ALLOWED,
    private val title: String = DEFAULT_TITLE,
    private val language: String = DEFAULT_LANGUAGE,
) {
    fun execute(
        puzzleId: UUID,
        width: Int? = null,
        height: Int? = null,
    ): StoredPuzzle? =
        puzzleRepository.getOrCompute(puzzleId) {
            val grid = generatePuzzle.execute(width = width, height = height) ?: return@getOrCompute null
            StoredPuzzle(
                grid = grid,
                title = title,
                language = language,
                hintsAllowed = hintsAllowed,
                createdAt = clock.instant(),
            )
        }

    companion object {
        const val DEFAULT_HINTS_ALLOWED: Int = 3
        const val DEFAULT_TITLE: String = "Grille du jour"
        const val DEFAULT_LANGUAGE: String = "fr"
    }
}
