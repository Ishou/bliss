package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordClue
import org.junit.jupiter.api.Test

class SlotRegistryTest {
    private fun mkLexicon(words: List<String>): Lexicon =
        Lexicon(ListWordRepository(words.map { Word(text = it, clues = listOf(WordClue("c-$it"))) }))

    private fun cellsFrom(layout: String): CellArray {
        val rows = layout.trimIndent().lines().filter { it.isNotBlank() }
        val h = rows.size
        val w = rows[0].length
        val cells = CellArray(w, h)
        for (r in 0 until h) {
            for (c in 0 until w) {
                if (rows[r][c] == '#') cells.set(r, c, CellArray.BLACK)
            }
        }
        return cells
    }

    /**
     * A 5x5 boundary-skeleton layout — row 0 and col 0 alternate BLACK/LETTER
     * at even/odd indices. All resulting slots have valid clue positions.
     *
     *   # . # . #
     *   . . . . .
     *   # . . . .
     *   . . . . .
     *   # . . . .
     */
    private val standard5x5 =
        """
        #.#.#
        .....
        #....
        .....
        #....
        """

    private val lexFor5x5 =
        mkLexicon(
            listOf(
                "AB", "CD", "EF", "GH", "IJ", "KL", "MN", "OP", "QR",
                "ABC", "DEF", "GHI", "JKL", "MNO", "PQR",
                "ABCD", "EFGH", "IJKL", "MNOP", "QRST",
                "ABCDE", "FGHIJ", "KLMNO", "PQRST", "UVWXY",
            ),
        )

    @Test
    fun `interior horizontal slot uses RIGHT direction with clue one column left`() {
        val cells = cellsFrom(standard5x5)
        val build = SlotRegistry.build(cells, lexFor5x5, minLen = 2)
        assertThat(build).isNotNull()
        // Row 2 horizontal slot: (2,1)..(2,4) length 4, clue at (2,0) BLACK, RIGHT.
        val s = build!!.slots.find { it.axis == SlotAxis.HORIZONTAL && it.row == 2 && it.col == 1 }
        assertThat(s).isNotNull()
        assertThat(s!!.direction).isEqualTo(Direction.RIGHT)
        assertThat(s.cluePosition).isEqualTo(Position(Row(2), Column(0)))
        assertThat(s.length).isEqualTo(4)
    }

    @Test
    fun `horizontal slot in row 1 starting at column 0 uses DOWN_RIGHT with clue above`() {
        val cells = cellsFrom(standard5x5)
        val build = SlotRegistry.build(cells, lexFor5x5, minLen = 2)!!
        // Row 1 horizontal slot: (1,0)..(1,4) length 5, clue at (0,0) BLACK, DOWN_RIGHT.
        val s = build.slots.find { it.axis == SlotAxis.HORIZONTAL && it.row == 1 && it.col == 0 }
        assertThat(s).isNotNull()
        assertThat(s!!.direction).isEqualTo(Direction.DOWN_RIGHT)
        assertThat(s.cluePosition).isEqualTo(Position(Row(0), Column(0)))
    }

    @Test
    fun `vertical slot in column 1 starting at row 0 uses RIGHT_DOWN with clue at corner left`() {
        val cells = cellsFrom(standard5x5)
        val build = SlotRegistry.build(cells, lexFor5x5, minLen = 2)!!
        // Column 1 vertical slot: (0,1)..(4,1) length 5, clue at (0,0) BLACK, RIGHT_DOWN.
        val s = build.slots.find { it.axis == SlotAxis.VERTICAL && it.row == 0 && it.col == 1 }
        assertThat(s).isNotNull()
        assertThat(s!!.direction).isEqualTo(Direction.RIGHT_DOWN)
        assertThat(s.cluePosition).isEqualTo(Position(Row(0), Column(0)))
    }

    @Test
    fun `interior vertical slot uses DOWN with clue above`() {
        val cells = cellsFrom(standard5x5)
        val build = SlotRegistry.build(cells, lexFor5x5, minLen = 2)!!
        // Column 2 vertical slot: (1,2)..(4,2) length 4, clue at (0,2) BLACK, DOWN.
        val s = build.slots.find { it.axis == SlotAxis.VERTICAL && it.row == 1 && it.col == 2 }
        assertThat(s).isNotNull()
        assertThat(s!!.direction).isEqualTo(Direction.DOWN)
        assertThat(s.cluePosition).isEqualTo(Position(Row(0), Column(2)))
    }

    @Test
    fun `crossings link H and V slots at shared cells`() {
        val cells = cellsFrom(standard5x5)
        val build = SlotRegistry.build(cells, lexFor5x5, minLen = 2)!!
        // Row 1 horizontal slot crosses every column-vertical slot.
        val rowSlot = build.slots.first { it.axis == SlotAxis.HORIZONTAL && it.row == 1 && it.col == 0 }
        val col1Slot = build.slots.first { it.axis == SlotAxis.VERTICAL && it.col == 1 }
        // rowSlot positions: (1,0), (1,1), (1,2), (1,3), (1,4). Position 1 == (1, 1).
        assertThat(rowSlot.crossings[1]).hasSize(1)
        val (otherSid, otherPos) = rowSlot.crossings[1][0]
        assertThat(otherSid).isEqualTo(col1Slot.sid)
        // col1Slot is length 5 starting at row 0. (1, 1) is at index 1.
        assertThat(otherPos).isEqualTo(1)
    }

    @Test
    fun `slot shorter than minLen is dropped`() {
        // 5x5: a length-1 run gets dropped (single white cell between two blacks).
        //   # . # . #
        //   . . # . .   ← (1, 2) BLACK creates length-1 runs in row 1? no, (1,0)(1,1) length 2 and (1,3)(1,4) length 2
        //   # # . . .   ← (2, 0) BLACK + (2, 1) BLACK: (2,2)(2,3)(2,4) length 3, no row-2 length-1
        //   . . . . .
        //   # . . . .
        // Try this: a length-1 white run on row 0 between blacks:
        //   # # . # #   ← (0, 2) white, length-1 run at col 2
        //   . . . . .
        //   # . . . .
        //   . . . . .
        //   # . . . .
        val cells = cellsFrom(
            """
            ##.##
            .....
            #....
            .....
            #....
            """
        )
        val build = SlotRegistry.build(cells, lexFor5x5, minLen = 2)!!
        // Row 0's length-1 white run at column 2 should NOT yield a horizontal slot.
        assertThat(build.slots.none { it.axis == SlotAxis.HORIZONTAL && it.row == 0 }).isTrue()
    }

    @Test
    fun `build returns null if any slot length has no corpus`() {
        // 6x6 standard skeleton with a length-5 run; corpus only has length 2/3.
        val cells = cellsFrom(
            """
            #.#.#.
            ......
            #.....
            ......
            #.....
            ......
            """
        )
        val sparseLex = mkLexicon(listOf("AB", "CD", "EF", "ABC", "DEF"))
        val build = SlotRegistry.build(cells, sparseLex, minLen = 2)
        assertThat(build).isNull()
    }

    @Test
    fun `build returns null when a slot has no valid on-grid clue position`() {
        // Layout breaks the boundary invariant: (0, 0) is WHITE.
        // Column 0 starts at row 0 with no clue available.
        val cells = cellsFrom(
            """
            ...
            ...
            ...
            """
        )
        val build = SlotRegistry.build(cells, mkLexicon(listOf("AB", "ABC")), minLen = 2)
        assertThat(build).isNull()
    }
}
