package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SkeletonTest {
    @Test
    fun `2x2 grid has only the corner dual cell`() {
        val arrows = Skeleton.arrows(2, 2)
        assertThat(arrows).containsExactlyInAnyOrder(
            ClueArrow(pos(0, 0), Direction.DOWN_RIGHT),
            ClueArrow(pos(0, 0), Direction.RIGHT_DOWN),
        )
    }

    @Test
    fun `even-sized 10x10 has 5 dual cells per axis, no trailing single`() {
        val arrows = Skeleton.arrows(10, 10)
        // Top row: corner + 4 inner duals = 5 cells × {2 arrows} except corner is DOWN_RIGHT+RIGHT_DOWN (not RIGHT_DOWN+DOWN).
        val topRowClues = arrows.filter { it.cluePosition.row.value == 0 }.map { it.cluePosition }.toSet()
        assertThat(topRowClues).isEqualTo(setOf(pos(0, 0), pos(0, 2), pos(0, 4), pos(0, 6), pos(0, 8)))
        val leftColClues = arrows.filter { it.cluePosition.column.value == 0 }.map { it.cluePosition }.toSet()
        assertThat(leftColClues).isEqualTo(setOf(pos(0, 0), pos(2, 0), pos(4, 0), pos(6, 0), pos(8, 0)))
    }

    @Test
    fun `odd width adds a single DOWN clue at the last column`() {
        val arrows = Skeleton.arrows(11, 10)
        val last = arrows.filter { it.cluePosition == pos(0, 10) }
        assertThat(last).isEqualTo(listOf(ClueArrow(pos(0, 10), Direction.DOWN)))
    }

    @Test
    fun `odd height adds a single RIGHT clue at the last row`() {
        val arrows = Skeleton.arrows(10, 11)
        val last = arrows.filter { it.cluePosition == pos(10, 0) }
        assertThat(last).isEqualTo(listOf(ClueArrow(pos(10, 0), Direction.RIGHT)))
    }

    @Test
    fun `corner is always DOWN_RIGHT plus RIGHT_DOWN`() {
        val arrows = Skeleton.arrows(8, 8)
        val cornerArrows = arrows.filter { it.cluePosition == pos(0, 0) }.map { it.direction }.toSet()
        assertThat(cornerArrows).isEqualTo(setOf(Direction.DOWN_RIGHT, Direction.RIGHT_DOWN))
    }

    @Test
    fun `inner top-row dual cells are RIGHT_DOWN plus DOWN`() {
        val arrows = Skeleton.arrows(10, 10)
        for (col in listOf(2, 4, 6, 8)) {
            val dirs = arrows.filter { it.cluePosition == pos(0, col) }.map { it.direction }.toSet()
            assertThat(dirs).isEqualTo(setOf(Direction.RIGHT_DOWN, Direction.DOWN))
        }
    }

    @Test
    fun `inner left-col dual cells are DOWN_RIGHT plus RIGHT`() {
        val arrows = Skeleton.arrows(10, 10)
        for (row in listOf(2, 4, 6, 8)) {
            val dirs = arrows.filter { it.cluePosition == pos(row, 0) }.map { it.direction }.toSet()
            assertThat(dirs).isEqualTo(setOf(Direction.DOWN_RIGHT, Direction.RIGHT))
        }
    }

    @Test
    fun `1x1 and degenerate sizes are rejected`() {
        assertThrows<IllegalArgumentException> { Skeleton.arrows(1, 5) }
        assertThrows<IllegalArgumentException> { Skeleton.arrows(5, 1) }
        assertThrows<IllegalArgumentException> { Skeleton.arrows(0, 0) }
    }

    @Test
    fun `every clue cell sits on the top row or left column`() {
        for (w in 2..12) {
            for (h in 2..12) {
                val arrows = Skeleton.arrows(w, h)
                val offBoundary =
                    arrows
                        .map { it.cluePosition }
                        .filter { it.row.value != 0 && it.column.value != 0 }
                assertThat(offBoundary).isEmpty()
            }
        }
    }

    private fun pos(
        row: Int,
        col: Int,
    ): Position = Position(Row(row), Column(col))
}
