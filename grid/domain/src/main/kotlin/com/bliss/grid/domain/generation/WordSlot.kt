package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row

/**
 * A planned word slot — where the word's letters live, what direction they flow,
 * and which clue cell anchors the arrow. The word itself is not yet known; the
 * filler maps each [WordSlot] to a concrete `Word` from the corpus.
 *
 * Coordinates are absolute grid positions. [length] is the number of letter cells
 * the slot occupies (≥ 2).
 */
data class WordSlot(
    val cluePosition: Position,
    val direction: Direction,
    val length: Int,
) {
    init {
        require(length >= 2) { "slot length must be ≥ 2, was $length" }
    }

    /** Position of the first letter — clue position + direction's startOffset. */
    val firstLetter: Position by lazy {
        Position(
            Row(cluePosition.row.value + direction.startOffset.row.value),
            Column(cluePosition.column.value + direction.startOffset.column.value),
        )
    }

    /**
     * All letter cells of this slot, in flow order. Memoized — the filler calls
     * this hot in the inner loop (per slot per search node), and a fresh list
     * allocation each time dominated GC on the 15×12 default constraints.
     */
    private val cachedLetterPositions: List<Position> by lazy {
        val r0 = firstLetter.row.value
        val c0 = firstLetter.column.value
        val dr = direction.step.row.value
        val dc = direction.step.column.value
        List(length) { i -> Position(Row(r0 + dr * i), Column(c0 + dc * i)) }
    }

    fun letterPositions(): List<Position> = cachedLetterPositions
}
