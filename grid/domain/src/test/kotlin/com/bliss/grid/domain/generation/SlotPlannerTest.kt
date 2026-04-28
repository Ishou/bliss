package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import org.junit.jupiter.api.Test

class SlotPlannerTest {
    @Test
    fun `full-length slot for 0,0 DOWN_RIGHT spans entire row 1`() {
        val slots = SlotPlanner.planFullLength(Skeleton.arrows(10, 10), 10, 10)
        val row1Slot =
            slots.single {
                it.cluePosition == pos(0, 0) && it.direction == Direction.DOWN_RIGHT
            }
        assertThat(row1Slot.length).isEqualTo(10)
        assertThat(row1Slot.firstLetter).isEqualTo(pos(1, 0))
        assertThat(row1Slot.letterPositions().last()).isEqualTo(pos(1, 9))
    }

    @Test
    fun `full-length slot for 0,0 RIGHT_DOWN spans entire col 1`() {
        val slots = SlotPlanner.planFullLength(Skeleton.arrows(10, 10), 10, 10)
        val col1Slot =
            slots.single {
                it.cluePosition == pos(0, 0) && it.direction == Direction.RIGHT_DOWN
            }
        assertThat(col1Slot.length).isEqualTo(10)
        assertThat(col1Slot.firstLetter).isEqualTo(pos(0, 1))
        assertThat(col1Slot.letterPositions().last()).isEqualTo(pos(9, 1))
    }

    @Test
    fun `full-length slot for 2,0 RIGHT_DOWN runs from row 0 in col 3 for 10 rows`() {
        // RIGHT_DOWN clue at (row=0, col=2): word starts at (row=0, col=3) flowing down.
        val slots = SlotPlanner.planFullLength(Skeleton.arrows(10, 10), 10, 10)
        val s = slots.single { it.cluePosition == pos(0, 2) && it.direction == Direction.RIGHT_DOWN }
        assertThat(s.length).isEqualTo(10)
        assertThat(s.firstLetter).isEqualTo(pos(0, 3))
    }

    @Test
    fun `full-length slot for 2,0 DOWN runs from row 1 in col 2 for 9 rows`() {
        // DOWN clue at (row=0, col=2): word starts at (row=1, col=2) flowing down.
        val slots = SlotPlanner.planFullLength(Skeleton.arrows(10, 10), 10, 10)
        val s = slots.single { it.cluePosition == pos(0, 2) && it.direction == Direction.DOWN }
        assertThat(s.length).isEqualTo(9)
        assertThat(s.firstLetter).isEqualTo(pos(1, 2))
    }

    @Test
    fun `full-length slot for 0,2 RIGHT runs from col 1 in row 2 for 9 cols`() {
        // RIGHT clue at (row=2, col=0): word starts at (row=2, col=1) flowing right.
        val slots = SlotPlanner.planFullLength(Skeleton.arrows(10, 10), 10, 10)
        val s = slots.single { it.cluePosition == pos(2, 0) && it.direction == Direction.RIGHT }
        assertThat(s.length).isEqualTo(9)
        assertThat(s.firstLetter).isEqualTo(pos(2, 1))
    }

    @Test
    fun `every letter cell of a 10x10 plan stays in bounds and is not a clue cell`() {
        val w = 10
        val h = 10
        val arrows = Skeleton.arrows(w, h)
        val clueCells = arrows.map { it.cluePosition }.toSet()
        val slots = SlotPlanner.planFullLength(arrows, w, h)
        val letters = SlotPlanner.letterCells(slots)

        for (p in letters) {
            assertThat(p.row.value in 0 until h).isTrue()
            assertThat(p.column.value in 0 until w).isTrue()
        }
        assertThat(clueCells.intersect(letters).isEmpty()).isTrue()
    }

    @Test
    fun `10x10 plan covers every non-clue cell at least once`() {
        // With v1 (full length), the union of letter cells across all slots
        // must equal the grid minus the boundary clue cells.
        val w = 10
        val h = 10
        val arrows = Skeleton.arrows(w, h)
        val clueCells = arrows.map { it.cluePosition }.toSet()
        val slots = SlotPlanner.planFullLength(arrows, w, h)
        val letters = SlotPlanner.letterCells(slots)
        val expectedLetters =
            (0 until h).flatMap { r ->
                (0 until w).map { c -> Position(Row(r), Column(c)) }
            }.filter { it !in clueCells }.toSet()
        assertThat(letters).isEqualTo(expectedLetters)
    }

    @Test
    fun `odd-width 11x10 single DOWN clue produces a slot of length 9 in col 10`() {
        val slots = SlotPlanner.planFullLength(Skeleton.arrows(11, 10), 11, 10)
        val s = slots.single { it.cluePosition == pos(0, 10) && it.direction == Direction.DOWN }
        assertThat(s.length).isEqualTo(9) // rows 1..9
        assertThat(s.firstLetter).isEqualTo(pos(1, 10))
    }

    @Test
    fun `odd-height 10x11 single RIGHT clue produces a slot of length 9 in row 10`() {
        val slots = SlotPlanner.planFullLength(Skeleton.arrows(10, 11), 10, 11)
        val s = slots.single { it.cluePosition == pos(10, 0) && it.direction == Direction.RIGHT }
        assertThat(s.length).isEqualTo(9) // cols 1..9
        assertThat(s.firstLetter).isEqualTo(pos(10, 1))
    }

    private fun pos(
        row: Int,
        col: Int,
    ): Position = Position(Row(row), Column(col))
}
