package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row

/**
 * One arrow on a clue cell — a starting point for a word.
 * [cluePosition] is the cell that holds the arrow; [direction] defines where the
 * word's first letter sits and which way letters flow (see [Direction]).
 *
 * A clue cell may carry one or two arrows. Two arrows on the same cell mean the
 * cell is dual (e.g. the (0, 0) corner carries DOWN_RIGHT + RIGHT_DOWN).
 */
data class ClueArrow(
    val cluePosition: Position,
    val direction: Direction,
)

/**
 * Deterministic boundary clue layout that every generated grid starts from.
 *
 * Top row (row = 0):
 * - (0, 0): dual `DOWN_RIGHT + RIGHT_DOWN`. The only corner with this combination —
 *   `DOWN_RIGHT` starts the row-1 horizontal word, `RIGHT_DOWN` starts the col-1
 *   vertical word.
 * - (0, 2k) for 1 ≤ k, 2k ≤ width − 2: dual `RIGHT_DOWN + DOWN`.
 *   `RIGHT_DOWN` starts a vertical word in column 2k+1 from row 0, `DOWN` starts a
 *   vertical word in column 2k from row 1.
 * - If width is odd: (0, width − 1) carries a single `DOWN`. The last column has no
 *   room for a `RIGHT_DOWN` arrow (its target column would be out of bounds).
 *
 * Left column (column = 0):
 * - (2k, 0) for 1 ≤ k, 2k ≤ height − 2: dual `DOWN_RIGHT + RIGHT`.
 *   `DOWN_RIGHT` starts a horizontal word in row 2k+1 from col 0, `RIGHT` starts a
 *   horizontal word in row 2k from col 1.
 * - If height is odd: (height − 1, 0) carries a single `RIGHT`.
 *
 * The boundary cells at *odd* indices on the top row and left column are letter
 * cells — each holds the first letter of the perpendicular word that the adjacent
 * even-indexed clue cell starts.
 *
 * Pure function over (width, height) — no randomness, no state. The slot planner
 * downstream is what introduces variability via word-length choices and trailing
 * clue-cell insertions.
 */
object Skeleton {
    fun arrows(
        width: Int,
        height: Int,
    ): List<ClueArrow> {
        require(width >= 2 && height >= 2) { "grid must be at least 2×2, was ${width}×$height" }
        val arrows = mutableListOf<ClueArrow>()

        // Corner (0, 0) — dual DOWN_RIGHT + RIGHT_DOWN.
        arrows += ClueArrow(pos(0, 0), Direction.DOWN_RIGHT)
        arrows += ClueArrow(pos(0, 0), Direction.RIGHT_DOWN)

        // Top row dual cells at even columns from 2 up to width − 2.
        var c = 2
        while (c <= width - 2) {
            arrows += ClueArrow(pos(0, c), Direction.RIGHT_DOWN)
            arrows += ClueArrow(pos(0, c), Direction.DOWN)
            c += 2
        }
        if (width % 2 == 1) {
            arrows += ClueArrow(pos(0, width - 1), Direction.DOWN)
        }

        // Left column dual cells at even rows from 2 up to height − 2.
        var r = 2
        while (r <= height - 2) {
            arrows += ClueArrow(pos(r, 0), Direction.DOWN_RIGHT)
            arrows += ClueArrow(pos(r, 0), Direction.RIGHT)
            r += 2
        }
        if (height % 2 == 1) {
            arrows += ClueArrow(pos(height - 1, 0), Direction.RIGHT)
        }

        return arrows
    }

    private fun pos(
        row: Int,
        col: Int,
    ): Position = Position(Row(row), Column(col))
}
