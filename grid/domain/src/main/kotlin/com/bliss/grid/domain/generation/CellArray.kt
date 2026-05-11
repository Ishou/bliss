package com.bliss.grid.domain.generation

/**
 * Mutable cell substrate for the bitmask-CSP generator. Pure data; no
 * undo log here (the trail-based undo lives in [BitmaskCsp]).
 *
 * Cell encoding via [Int]:
 *  - [BLACK] (`-1`): a clue (black) cell.
 *  - [EMPTY] (`-2`): a white cell with no letter committed yet.
 *  - `0..25`: a letter index (`0 ↔ 'A'`, `25 ↔ 'Z'`).
 *
 * Construction allocates a `H × W` int matrix initialised to [EMPTY].
 */
internal class CellArray(
    val width: Int,
    val height: Int,
) {
    init {
        require(width >= 2 && height >= 2) {
            "grid must be at least 2×2, was ${width}×$height"
        }
    }

    /** `cells[r][c]` ∈ {BLACK, EMPTY, 0..25}. Row-major. */
    private val cells: IntArray = IntArray(width * height) { EMPTY }

    /** Read a cell value. */
    fun get(
        r: Int,
        c: Int,
    ): Int {
        require(r in 0 until height && c in 0 until width) { "(r=$r, c=$c) out of bounds" }
        return cells[r * width + c]
    }

    /** Write a cell value. */
    fun set(
        r: Int,
        c: Int,
        value: Int,
    ) {
        require(r in 0 until height && c in 0 until width) { "(r=$r, c=$c) out of bounds" }
        require(value == BLACK || value == EMPTY || value in 0..25) {
            "invalid cell value $value; must be BLACK, EMPTY, or 0..25"
        }
        cells[r * width + c] = value
    }

    fun isBlack(
        r: Int,
        c: Int,
    ): Boolean = get(r, c) == BLACK

    fun isEmpty(
        r: Int,
        c: Int,
    ): Boolean = get(r, c) == EMPTY

    fun isLetter(
        r: Int,
        c: Int,
    ): Boolean = get(r, c) in 0..25

    /** Wipe every non-black cell back to [EMPTY]. Used between restart attempts. */
    fun clearLetters() {
        for (i in cells.indices) {
            if (cells[i] >= 0) cells[i] = EMPTY
        }
    }

    /** Total number of [BLACK] cells. */
    fun countBlack(): Int = cells.count { it == BLACK }

    /** Defensive shallow copy. */
    fun copy(): CellArray {
        val out = CellArray(width, height)
        cells.copyInto(out.cells)
        return out
    }

    companion object {
        const val BLACK: Int = -1
        const val EMPTY: Int = -2
    }
}
