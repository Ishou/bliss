package com.bliss.grid.application.puzzle

import com.bliss.grid.application.analytics.AnalyticsEventSink
import com.bliss.grid.domain.analytics.AnalyticsEvent
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.LetterCell
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import java.sql.Connection
import java.util.UUID

/**
 * Spends one hint to reveal the canonical solution letter at a single cell.
 *
 * Flow:
 *  1. Resolve the puzzle in the store — `PuzzleNotFound` if it has never
 *     been GET-ed.
 *  2. Validate that `(row, column)` is in bounds and points at a letter
 *     cell. A bad coordinate is rejected as `InvalidCoord` and does NOT
 *     decrement the budget.
 *  3. Spend a hint atomically against `(puzzleId, userId)` on the
 *     caller-supplied [conn] — `BudgetExhausted` when the cap is reached.
 *  4. Read the canonical letter at the cell and echo `(row, column)`.
 *
 * The route wraps this call in [HintWriteCoordinator.withUserLock] so the
 * spend happens inside a per-user advisory-lock transaction; the under-
 * lock fresh cookie re-verify already happened in the route handler.
 */
class RevealCellHintUseCase(
    private val puzzleRepository: PuzzleRepository,
    private val hintUsageRepository: HintUsageRepository,
    private val analyticsEventSink: AnalyticsEventSink = AnalyticsEventSink.Noop,
) {
    fun execute(
        conn: Connection,
        puzzleId: UUID,
        userId: UUID,
        row: Int,
        column: Int,
    ): RevealCellHintOutcome {
        val puzzle = puzzleRepository.get(puzzleId) ?: return RevealCellHintOutcome.PuzzleNotFound

        val grid = puzzle.grid
        if (row < 0 || row >= grid.height || column < 0 || column >= grid.width) {
            return RevealCellHintOutcome.InvalidCoord(
                "($row, $column) out of grid bounds (${grid.width}x${grid.height})",
            )
        }
        val position = Position(Row(row), Column(column))
        val cell = grid.cells[position]
        if (cell !is LetterCell) {
            return RevealCellHintOutcome.InvalidCoord(
                "($row, $column) does not point at a letter cell",
            )
        }

        val usedAfter =
            hintUsageRepository.trySpend(conn, puzzleId, userId, puzzle.hintsAllowed)
                ?: return RevealCellHintOutcome.BudgetExhausted

        analyticsEventSink.record(
            AnalyticsEvent.HintUsed(
                gridSize = "${grid.width}x${grid.height}",
                hintsUsedSoFar = usedAfter,
            ),
            userId,
        )
        return RevealCellHintOutcome.Granted(
            row = row,
            column = column,
            letter = cell.letter,
            hintsRemaining = puzzle.hintsAllowed - usedAfter,
        )
    }
}

sealed class RevealCellHintOutcome {
    /**
     * Hint spent successfully. [letter] is the canonical solution letter at
     * `(row, column)`; [hintsRemaining] is `hintsAllowed - hints_used after
     * spend` (so 0 means the next call will return `BudgetExhausted`).
     */
    data class Granted(
        val row: Int,
        val column: Int,
        val letter: Char,
        val hintsRemaining: Int,
    ) : RevealCellHintOutcome()

    /** No puzzle in the store for this id. Maps to 404 puzzle-not-found. */
    data object PuzzleNotFound : RevealCellHintOutcome()

    /** Per-(puzzle, user) cap reached. Maps to 429 hint-budget-exhausted. */
    data object BudgetExhausted : RevealCellHintOutcome()

    /** `(row, column)` out of bounds or not a letter cell. Maps to 400 invalid-coord. */
    data class InvalidCoord(
        val reason: String,
    ) : RevealCellHintOutcome()

    /** Fresh cookie re-verify failed under the lock (session was revoked). Maps to 401 auth-required. */
    data object SessionRevoked : RevealCellHintOutcome()
}
