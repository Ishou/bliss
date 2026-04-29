package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SkeletonFillerTest {
    private val future = System.currentTimeMillis() + 60_000L
    private val past = System.currentTimeMillis() - 1_000L
    private val random = Random(0)

    @Test
    fun `fills independent slots that share no cells`() {
        val filler =
            SkeletonFiller(
                ListWordRepository(
                    listOf(
                        Word("AIR", ""),
                        Word("EAU", ""),
                        Word("OR", ""),
                        Word("AS", ""),
                    ),
                ),
            )
        // Two horizontal slots on different rows — no shared cells.
        val slots =
            listOf(
                WordSlot(pos(0, 0), Direction.RIGHT, length = 3),
                WordSlot(pos(1, 0), Direction.RIGHT, length = 2),
            )
        assertThat(filler.fill(slots, random, future)).isNotNull()
    }

    @Test
    fun `propagates crossing letter to constrain intersecting slot`() {
        // Horizontal at (1,0) RIGHT length=4: letters (1,1),(1,2),(1,3),(1,4)
        // Vertical   at (0,2) DOWN  length=3: letters (1,2),(2,2),(3,2)
        // Shared cell (1,2): horizontal index 1, vertical index 0.
        //
        // 4-letter words: [ARTE, ROSE, MERE] (R / O / E at index 1).
        // 3-letter words: [ORS] (starts with O only).
        // MRV picks vertical first (1 candidate) → assigns ORS → horizontal
        // constrained to have O at index 1 → only ROSE matches → result is
        // [ROSE at slot 0, ORS at slot 1].
        val filler =
            SkeletonFiller(
                ListWordRepository(
                    listOf(
                        Word("ARTE", ""),
                        Word("ROSE", ""),
                        Word("MERE", ""),
                        Word("ORS", ""),
                    ),
                ),
            )
        val slots =
            listOf(
                WordSlot(pos(1, 0), Direction.RIGHT, length = 4),
                WordSlot(pos(0, 2), Direction.DOWN, length = 3),
            )
        val result = filler.fill(slots, random, future)
        assertThat(result).isNotNull()
        val byClue = result!!.associateBy { it.cluePosition }
        assertThat(byClue[pos(1, 0)]!!.word.text).isEqualTo("ROSE")
        assertThat(byClue[pos(0, 2)]!!.word.text).isEqualTo("ORS")
    }

    @Test
    fun `backtracks and returns null when no consistent assignment exists`() {
        // Same crossing geometry as above, but no 3-letter word starts with
        // O, R, or E (the characters that horizontal words place at index 1).
        // MRV assigns IFS to vertical → horizontal needs I at index 1 → none
        // of [ROSE, ARTE, MERE] match → backtrack → no more vertical candidates → null.
        val filler =
            SkeletonFiller(
                ListWordRepository(
                    listOf(
                        Word("ROSE", ""),
                        Word("ARTE", ""),
                        Word("MERE", ""),
                        Word("IFS", ""),
                    ),
                ),
            )
        val slots =
            listOf(
                WordSlot(pos(1, 0), Direction.RIGHT, length = 4),
                WordSlot(pos(0, 2), Direction.DOWN, length = 3),
            )
        assertThat(filler.fill(slots, random, future)).isNull()
    }

    @Test
    fun `returns null immediately when deadline has already passed`() {
        val filler = SkeletonFiller(ListWordRepository(listOf(Word("OR", ""))))
        val slots = listOf(WordSlot(pos(0, 0), Direction.RIGHT, length = 2))
        assertThat(filler.fill(slots, random, past)).isNull()
    }

    @Test
    fun `returns empty list for empty slot list`() {
        val filler = SkeletonFiller(ListWordRepository(emptyList()))
        assertThat(filler.fill(emptyList(), random, future)).isEqualTo(emptyList())
    }

    private fun pos(
        row: Int,
        col: Int,
    ) = Position(Row(row), Column(col))
}
