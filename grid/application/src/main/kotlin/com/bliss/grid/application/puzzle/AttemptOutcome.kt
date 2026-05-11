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
