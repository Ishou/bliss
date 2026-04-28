package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Position

/**
 * Translates a list of [ClueArrow]s into [WordSlot]s.
 *
 * v1: every slot runs full-length — from its first letter to the grid edge along
 * the arrow's step direction. No trailing clue cells, no length variation. This
 * yields the densest possible layout the skeleton allows (every cell beyond the
 * boundary clue cells is a letter), which is enough to validate the skeleton +
 * filler end-to-end before layering variable lengths on top.
 *
 * v2 (planned): allow each slot to choose a length from `{M, M-1} ∪ [2, M-3]`
 * where M is the available cells, and insert trailing clue cells (which carry
 * their own arrows recursively) when the chosen length stops short of the edge.
 */
object SlotPlanner {
    /**
     * For each arrow, build a slot whose length is the maximum number of cells
     * that fit between the first letter and the grid edge along the step direction.
     * Skips arrows whose first letter is already out of bounds (defensive — the
     * skeleton should never produce these for w, h ≥ 2).
     */
    fun planFullLength(
        arrows: List<ClueArrow>,
        width: Int,
        height: Int,
    ): List<WordSlot> {
        require(width >= 2 && height >= 2) { "grid must be at least 2×2, was $width×$height" }
        return arrows.mapNotNull { arrow ->
            val firstRow = arrow.cluePosition.row.value + arrow.direction.startOffset.row.value
            val firstCol = arrow.cluePosition.column.value + arrow.direction.startOffset.column.value
            if (firstRow !in 0 until height || firstCol !in 0 until width) return@mapNotNull null

            val dr = arrow.direction.step.row.value
            val dc = arrow.direction.step.column.value
            val length = maxRunLength(firstRow, firstCol, dr, dc, width, height)
            // Defensive: every skeleton-derived first letter sits at the edge of
            // a run that's at least 2 cells long given w, h ≥ 2.
            if (length < 2) return@mapNotNull null

            WordSlot(arrow.cluePosition, arrow.direction, length)
        }
    }

    /** Count cells along (dr, dc) starting from (row, col) that stay in bounds. */
    private fun maxRunLength(
        row: Int,
        col: Int,
        dr: Int,
        dc: Int,
        width: Int,
        height: Int,
    ): Int {
        var n = 0
        var r = row
        var c = col
        while (r in 0 until height && c in 0 until width) {
            n++
            r += dr
            c += dc
        }
        return n
    }

    /** Set of cells used as letters across all slots — useful for grid-shape checks. */
    fun letterCells(slots: List<WordSlot>): Set<Position> = slots.flatMap { it.letterPositions() }.toSet()

    /**
     * v2 — variable-length plan with coordinated structural mutation.
     *
     * Backtracking search over slot lengths. Each pending arrow is materialised at a length
     * chosen from the valid set `{M, M-1} ∪ [2, M-3]` where M is the current available cells.
     * If the chosen length stops short of the maximum, a trailing clue cell is placed and
     * carries a continuation arrow in the same axis. Trailing clues may invalidate
     * perpendicular skeleton arrows (their first letter / mid-path now sits on a clue cell);
     * those arrows get deactivated when the planner picks them up later, leaving the
     * boundary cell non-dual.
     *
     * Final plan is validated to ensure no orphan cells (untouched or dropped letter cells)
     * and no dead clue cells (clue cells with no materialised arrow). Failure at validation
     * triggers backtracking to the last length choice.
     *
     * Returns `null` if no consistent plan can be found within the deadline.
     */
    fun planVariable(
        arrows: List<ClueArrow>,
        width: Int,
        height: Int,
        random: kotlin.random.Random,
        deadline: Long,
    ): List<WordSlot>? {
        require(width >= 2 && height >= 2) { "grid must be at least 2×2, was $width×$height" }
        val state = PlanState(width, height)
        for (arrow in arrows) {
            state.addClueCell(arrow.cluePosition)
            state.addArrow(arrow.cluePosition, arrow.direction)
        }
        return solveVariable(state, random, deadline)
    }

    private fun solveVariable(
        state: PlanState,
        random: kotlin.random.Random,
        deadline: Long,
    ): List<WordSlot>? {
        if (System.currentTimeMillis() > deadline) return null

        val next =
            state.nextPending() ?: run {
                // No more pending arrows → check final consistency.
                return if (state.validate().ok) state.slots.toList() else null
            }

        val available = state.availableLength(next)

        // Arrow can't fit a 2+ letter word — only option is to deactivate it.
        if (available < 2) {
            val cp = state.checkpoint()
            state.deactivate(next.cluePosition, next.direction)
            val result = solveVariable(state, random, deadline)
            if (result != null) return result
            state.rollback(cp)
            return null
        }

        // Length choice strategy:
        // 1. Filter to orphan-safe lengths (corner row 1 / col 1 only allow even L —
        //    odd L would deactivate a perpendicular skeleton arrow whose first letter
        //    is uniquely owned and would orphan).
        // 2. Bias toward shorter mid-range lengths (3..6) — typical mots-fléchés word
        //    sizes, and breaking long words into shorter sub-slots dramatically eases
        //    the filler's CSP (more candidates per slot, sparser intersection graph).
        val candidates = orphanSafeLengths(next, available)
        val ordering = orderForBias(candidates, random)
        for (length in ordering) {
            val cp = state.checkpoint()
            val materialised = state.materialize(next, length)
            if (materialised != null) {
                val result = solveVariable(state, random, deadline)
                if (result != null) return result
            }
            state.rollback(cp)
        }
        return null
    }

    /**
     * Lengths from [validLengths] further filtered for orphan-safety on the corner
     * arrows. The (0, 0) DOWN_RIGHT word (row 1) and (0, 0) RIGHT_DOWN word (col 1)
     * trail at perpendicular columns / rows; if those land at *odd* indices, the
     * perpendicular boundary arrow's uniquely-owned first letter (a row-0 / col-0
     * letter cell) gets deactivated and orphans. Restrict to even-indexed trails.
     *
     * For all other arrows, valid lengths are unconstrained beyond [validLengths] —
     * deactivated perpendicular arrows leave bare letter cells, which the loose
     * mots-fléchés model accepts.
     */
    private fun orphanSafeLengths(
        arrow: ClueArrow,
        available: Int,
    ): List<Int> {
        val all = validLengths(available)
        val isCornerRow1 =
            arrow.cluePosition.row.value == 0 &&
                arrow.cluePosition.column.value == 0 &&
                arrow.direction == com.bliss.grid.domain.model.Direction.DOWN_RIGHT
        val isCornerCol1 =
            arrow.cluePosition.row.value == 0 &&
                arrow.cluePosition.column.value == 0 &&
                arrow.direction == com.bliss.grid.domain.model.Direction.RIGHT_DOWN
        if (!isCornerRow1 && !isCornerCol1) return all
        // For corner arrows, only L = available (full length, no trail) or even L < available are safe.
        return all.filter { it == available || it % 2 == 0 }
    }

    /**
     * Order candidate lengths for backtracking. Bias: shorter mid-range (3..6) first,
     * then longer, then 2 last. Keeps variety random within each bucket so consecutive
     * runs don't repeat the same plan.
     */
    private fun orderForBias(
        candidates: List<Int>,
        random: kotlin.random.Random,
    ): List<Int> {
        val mid = candidates.filter { it in 3..6 }.shuffled(random)
        val long = candidates.filter { it > 6 }.shuffled(random)
        val short = candidates.filter { it == 2 }
        return mid + long + short
    }

    /**
     * Lengths in `{M, M-1} ∪ [2, M-3]`. M-2 is forbidden — it leaves exactly 1 trailing
     * cell after the trailing clue, which can't form a 2+ letter sub-word and would
     * orphan that cell.
     *
     * For small M:
     *   M=2 → {2}                        (only full)
     *   M=3 → {3, 2}                     (full or M-1)
     *   M=4 → {4, 3}                     (full and M-1; M-2=2 forbidden)
     *   M=5 → {5, 4, 2}                  (M-3 = 2 included)
     *   M=10 → {10, 9, 7, 6, 5, 4, 3, 2} (everything except M-2 = 8)
     */
    internal fun validLengths(available: Int): List<Int> {
        require(available >= 2)
        val result = ArrayList<Int>(available)
        result += available
        if (available >= 3) result += available - 1
        for (l in 2..(available - 3)) result += l
        return result
    }
}
