package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import org.junit.jupiter.api.Test

class GridShapeHashTest {
    private fun pos(
        r: Int,
        c: Int,
    ) = Position(Row(r), Column(c))

    private val slotsA =
        listOf(
            WordSlot(pos(0, 0), Direction.DOWN_RIGHT, 4),
            WordSlot(pos(0, 0), Direction.RIGHT_DOWN, 4),
            WordSlot(pos(0, 2), Direction.DOWN, 4),
        )

    @Test
    fun `same slots in different list orders hash equally`() {
        val a = GridShapeHash.of(slotsA)
        val b = GridShapeHash.of(slotsA.reversed())
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `same skeleton with different lengths produces different hashes`() {
        val differentLength =
            listOf(
                WordSlot(pos(0, 0), Direction.DOWN_RIGHT, 4),
                WordSlot(pos(0, 0), Direction.RIGHT_DOWN, 4),
                WordSlot(pos(0, 2), Direction.DOWN, 3),
            )
        assertThat(GridShapeHash.of(slotsA)).isNotEqualTo(GridShapeHash.of(differentLength))
    }

    @Test
    fun `same length set with different clue positions produces different hashes`() {
        val movedClue =
            listOf(
                WordSlot(pos(0, 0), Direction.DOWN_RIGHT, 4),
                WordSlot(pos(0, 0), Direction.RIGHT_DOWN, 4),
                WordSlot(pos(2, 0), Direction.RIGHT, 4),
            )
        assertThat(GridShapeHash.of(slotsA)).isNotEqualTo(GridShapeHash.of(movedClue))
    }

    @Test
    fun `hash is deterministic across calls`() {
        val a = GridShapeHash.of(slotsA)
        val b = GridShapeHash.of(slotsA)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `hash is never the FAIL sentinel`() {
        assertThat(GridShapeHash.of(slotsA)).isNotEqualTo("FAIL")
        assertThat(GridShapeHash.of(emptyList<WordSlot>())).isNotEqualTo("FAIL")
    }

    @Test
    fun `empty slot list still produces a valid hash`() {
        // An empty plan is a valid input (e.g. pre-fill snapshot of a 0-arrow
        // grid). The hash must be stable and not the FAIL sentinel.
        val a = GridShapeHash.of(emptyList<WordSlot>())
        val b = GridShapeHash.of(emptyList<WordSlot>())
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo("FAIL")
    }
}
