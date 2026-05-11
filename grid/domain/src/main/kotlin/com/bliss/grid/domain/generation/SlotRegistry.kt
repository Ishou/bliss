package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordClue
import com.bliss.grid.domain.model.WordPlacement

/**
 * One slot in the bitmask CSP — a maximal horizontal or vertical white
 * run of length ≥ minWordLength.
 *
 * Held by [SlotRegistry] as mutable state during the search.
 *
 * - [cluePosition] is the position of the BLACK cell that hosts the
 *   arrow pointing into this slot. For interior slots the clue sits one
 *   cell before the first letter (RIGHT / DOWN direction). For boundary
 *   slots starting at row 0 / column 0, the clue sits at the "corner"
 *   position (DOWN_RIGHT / RIGHT_DOWN direction).
 * - [direction] determines arrow type and downstream rendering.
 * - [cells]: ordered list of (r, c) positions for letters of this slot.
 * - [crossings]: per-position list of `(otherSid, otherPos)` neighbours.
 * - [domain] is a bitmask of viable word indices from `Lexicon.words[length]`.
 * - [assigned] is null until the search picks a word; then it points at
 *   the chosen word and clue.
 */
internal class Slot(
    val sid: Int,
    val axis: SlotAxis,
    val cluePosition: Position,
    val direction: Direction,
    val row: Int,
    val col: Int,
    val length: Int,
    val cells: List<Position>,
    val crossings: List<MutableList<Pair<Int, Int>>>,
    var domain: LongArray,
    var assigned: Word? = null,
    var chosenClue: WordClue? = null,
) {
    /** Sum of all per-position crossing counts. Used for degree tie-break in MRV. */
    val degree: Int get() = crossings.sumOf { it.size }

    /** True when this slot has been bound to a word by the search. */
    val isAssigned: Boolean get() = assigned != null
}

internal enum class SlotAxis { HORIZONTAL, VERTICAL }

/**
 * Builds the list of [Slot]s from a [CellArray]. Each maximal white run
 * of length ≥ [minLen] becomes a slot; crossings between H and V slots
 * are wired into the [Slot.crossings] structure.
 *
 * Returns `null` if any slot's length has no corpus words — that
 * layout is unbuildable and the caller (driver) should perturb + retry.
 */
internal object SlotRegistry {
    /**
     * Cell index lookup: `cellToSlots[r * width + c]` packs four ints
     * `(hSid, hPos, vSid, vPos)` with `-1` for absent. Returned alongside
     * the [Slot] list so the search can do `O(1)` crossing lookups.
     */
    class Build(
        val slots: List<Slot>,
        val cellToSlots: IntArray,
        val width: Int,
        val height: Int,
    ) {
        fun horizontalSlotAt(
            r: Int,
            c: Int,
        ): Int = cellToSlots[(r * width + c) * 4]

        fun horizontalPosAt(
            r: Int,
            c: Int,
        ): Int = cellToSlots[(r * width + c) * 4 + 1]

        fun verticalSlotAt(
            r: Int,
            c: Int,
        ): Int = cellToSlots[(r * width + c) * 4 + 2]

        fun verticalPosAt(
            r: Int,
            c: Int,
        ): Int = cellToSlots[(r * width + c) * 4 + 3]
    }

    /**
     * Construct the slot graph for [cells]. Returns `null` if any slot's
     * length has zero corpus words.
     */
    fun build(
        cells: CellArray,
        lexicon: Lexicon,
        minLen: Int,
    ): Build? {
        val w = cells.width
        val h = cells.height
        val slots = mutableListOf<Slot>()
        val cellToSlots = IntArray(w * h * 4) { -1 }

        // Horizontal pass: scan rows for maximal white runs.
        for (r in 0 until h) {
            var c = 0
            while (c < w) {
                if (cells.isBlack(r, c)) {
                    c++
                    continue
                }
                val startC = c
                val positions = mutableListOf<Position>()
                while (c < w && !cells.isBlack(r, c)) {
                    positions += Position(Row(r), Column(c))
                    c++
                }
                val length = positions.size
                if (length >= minLen) {
                    if (length > lexicon.maxLength) return null
                    val sid = slots.size
                    val (clue, dir) =
                        horizontalSlotClue(cells, r = r, startC = startC)
                            ?: return null
                    val initial = lexicon.initialMask(length)
                    if (lexicon.popcount(initial) == 0) return null
                    val crossings = List(length) { mutableListOf<Pair<Int, Int>>() }
                    val slot =
                        Slot(
                            sid = sid,
                            axis = SlotAxis.HORIZONTAL,
                            cluePosition = clue,
                            direction = dir,
                            row = r,
                            col = startC,
                            length = length,
                            cells = positions,
                            crossings = crossings,
                            domain = initial,
                        )
                    slots += slot
                    // Record cell → slot mapping for this horizontal slot.
                    positions.forEachIndexed { idx, pos ->
                        val base = (pos.row.value * w + pos.column.value) * 4
                        cellToSlots[base] = sid
                        cellToSlots[base + 1] = idx
                    }
                }
            }
        }

        // Vertical pass.
        for (col in 0 until w) {
            var r = 0
            while (r < h) {
                if (cells.isBlack(r, col)) {
                    r++
                    continue
                }
                val startR = r
                val positions = mutableListOf<Position>()
                while (r < h && !cells.isBlack(r, col)) {
                    positions += Position(Row(r), Column(col))
                    r++
                }
                val length = positions.size
                if (length >= minLen) {
                    if (length > lexicon.maxLength) return null
                    val sid = slots.size
                    val (clue, dir) =
                        verticalSlotClue(cells, c = col, startR = startR)
                            ?: return null
                    val initial = lexicon.initialMask(length)
                    if (lexicon.popcount(initial) == 0) return null
                    val crossings = List(length) { mutableListOf<Pair<Int, Int>>() }
                    val slot =
                        Slot(
                            sid = sid,
                            axis = SlotAxis.VERTICAL,
                            cluePosition = clue,
                            direction = dir,
                            row = startR,
                            col = col,
                            length = length,
                            cells = positions,
                            crossings = crossings,
                            domain = initial,
                        )
                    slots += slot
                    positions.forEachIndexed { idx, pos ->
                        val base = (pos.row.value * w + pos.column.value) * 4
                        cellToSlots[base + 2] = sid
                        cellToSlots[base + 3] = idx
                    }
                }
            }
        }

        // Wire crossings: every white cell with both H and V slots links them.
        for (r in 0 until h) {
            for (c in 0 until w) {
                if (cells.isBlack(r, c)) continue
                val base = (r * w + c) * 4
                val hSid = cellToSlots[base]
                val hPos = cellToSlots[base + 1]
                val vSid = cellToSlots[base + 2]
                val vPos = cellToSlots[base + 3]
                if (hSid >= 0 && vSid >= 0) {
                    slots[hSid].crossings[hPos] += (vSid to vPos)
                    slots[vSid].crossings[vPos] += (hSid to hPos)
                }
            }
        }

        return Build(slots, cellToSlots, w, h)
    }

    /**
     * For a horizontal slot at row `r` starting at column `startC`,
     * return the (clue position, direction) pair, or `null` if the slot
     * has no valid BLACK clue position (layout is invalid).
     *
     * Cases:
     *  - `startC > 0` AND `(r, startC - 1)` is BLACK: clue there, `RIGHT`.
     *  - `startC == 0 && r > 0` AND `(r - 1, 0)` is BLACK: clue there,
     *    `DOWN_RIGHT`.
     *  - Otherwise: invalid (the cell that would host the clue is
     *    actually a letter cell, or doesn't exist).
     */
    private fun horizontalSlotClue(
        cells: CellArray,
        r: Int,
        startC: Int,
    ): Pair<Position, Direction>? =
        when {
            startC > 0 && cells.isBlack(r, startC - 1) ->
                Position(Row(r), Column(startC - 1)) to Direction.RIGHT
            startC == 0 && r > 0 && cells.isBlack(r - 1, 0) ->
                Position(Row(r - 1), Column(0)) to Direction.DOWN_RIGHT
            else -> null
        }

    /**
     * For a vertical slot at column `c` starting at row `startR`:
     *  - `startR > 0` AND `(startR - 1, c)` is BLACK: clue there, `DOWN`.
     *  - `startR == 0 && c > 0` AND `(0, c - 1)` is BLACK: clue there,
     *    `RIGHT_DOWN`.
     *  - Otherwise: invalid.
     */
    private fun verticalSlotClue(
        cells: CellArray,
        c: Int,
        startR: Int,
    ): Pair<Position, Direction>? =
        when {
            startR > 0 && cells.isBlack(startR - 1, c) ->
                Position(Row(startR - 1), Column(c)) to Direction.DOWN
            startR == 0 && c > 0 && cells.isBlack(0, c - 1) ->
                Position(Row(0), Column(c - 1)) to Direction.RIGHT_DOWN
            else -> null
        }

    /** Build the [WordPlacement] list from solved slots. */
    fun toPlacements(slots: List<Slot>): List<WordPlacement> =
        slots.mapNotNull { slot ->
            val word = slot.assigned ?: return@mapNotNull null
            val clue = slot.chosenClue ?: return@mapNotNull null
            WordPlacement(word, slot.cluePosition, slot.direction, clue)
        }
}
