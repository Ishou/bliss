package com.bliss.grid.application.puzzle

import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.LetterCell
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import java.util.UUID

/**
 * Verifies a filled grid against the canonical solution stored for the
 * puzzleId, returning a position-only diff (never the canonical letter).
 *
 * Flow:
 *  1. Resolve the puzzle in the store — `PuzzleNotFound` if never GET-ed.
 *  2. Validate request body — bounds, single uppercase A-Z, point at letter
 *     cells, no duplicate (row, column).
 *  3. Walk every letter cell in the puzzle's grid; mark mismatches and
 *     unfilled positions.
 *  4. `solved = incorrectCells.isEmpty()`.
 *
 * The response intentionally omits the canonical letter at a wrong cell —
 * the client knows what it submitted, so it learns "this letter is wrong"
 * but not "the right letter is X". Brute-force letter extraction would
 * require `O(width × height × 26)` legal calls; that's mitigated at the
 * edge via rate limiting (ops concern, not in this contract).
 */
class ValidatePuzzleUseCase(
    private val puzzleRepository: PuzzleRepository,
) {
    fun execute(
        puzzleId: UUID,
        filled: List<FilledCellInput>,
    ): ValidatePuzzleOutcome {
        val puzzle = puzzleRepository.get(puzzleId) ?: return ValidatePuzzleOutcome.PuzzleNotFound

        val grid = puzzle.grid
        val byPosition = mutableMapOf<Position, Char>()
        for (entry in filled) {
            if (entry.row < 0 || entry.row >= grid.height || entry.column < 0 || entry.column >= grid.width) {
                return ValidatePuzzleOutcome.RequestInvalid(
                    "filledCell (${entry.row}, ${entry.column}) out of grid bounds (${grid.width}x${grid.height})",
                )
            }
            if (entry.letter.length != 1 || entry.letter[0] !in 'A'..'Z') {
                return ValidatePuzzleOutcome.RequestInvalid(
                    "letter must be a single uppercase A-Z; got '${entry.letter}'",
                )
            }
            val pos = Position(Row(entry.row), Column(entry.column))
            val cell = grid.cells[pos]
            if (cell !is LetterCell) {
                return ValidatePuzzleOutcome.RequestInvalid(
                    "filledCell (${entry.row}, ${entry.column}) does not point at a letter cell",
                )
            }
            if (byPosition.put(pos, entry.letter[0]) != null) {
                return ValidatePuzzleOutcome.RequestInvalid(
                    "duplicate filledCell at (${entry.row}, ${entry.column})",
                )
            }
        }

        val incorrect = mutableListOf<Position>()
        for ((pos, cell) in grid.cells) {
            if (cell !is LetterCell) continue
            val submitted = byPosition[pos]
            if (submitted == null || submitted != cell.letter) {
                incorrect += pos
            }
        }
        // Stable ordering for deterministic responses.
        incorrect.sortWith(compareBy({ it.row.value }, { it.column.value }))
        return ValidatePuzzleOutcome.Result(
            solved = incorrect.isEmpty(),
            incorrectCells = incorrect.toList(),
        )
    }
}

/**
 * Plain Kotlin shape for a submitted cell. The route layer parses the wire
 * `FilledCellDto` into this; the use case stays free of wire types.
 */
data class FilledCellInput(
    val row: Int,
    val column: Int,
    val letter: String,
)

sealed class ValidatePuzzleOutcome {
    /**
     * Validation completed. [incorrectCells] is empty iff [solved] is true.
     * Cells not filled by the client appear in [incorrectCells] alongside
     * cells whose submitted letter is wrong.
     */
    data class Result(
        val solved: Boolean,
        val incorrectCells: List<Position>,
    ) : ValidatePuzzleOutcome()

    /** No puzzle in the store for this id. Maps to 404 puzzle-not-found. */
    data object PuzzleNotFound : ValidatePuzzleOutcome()

    /**
     * Request body invalid — out-of-range position, non-letter cell target,
     * malformed letter, or duplicate (row, column). Maps to
     * 400 invalid-validate-request.
     */
    data class RequestInvalid(
        val reason: String,
    ) : ValidatePuzzleOutcome()
}
