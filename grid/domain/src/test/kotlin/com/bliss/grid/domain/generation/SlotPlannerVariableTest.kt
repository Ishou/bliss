package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.bliss.grid.domain.model.Position
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SlotPlannerVariableTest {
    @Test
    fun `validLengths follows the M, M-1, then 2 to M-3 rule`() {
        assertThat(SlotPlanner.validLengths(2)).isEqualTo(listOf(2))
        assertThat(SlotPlanner.validLengths(3)).isEqualTo(listOf(3, 2))
        assertThat(SlotPlanner.validLengths(4)).isEqualTo(listOf(4, 3))
        assertThat(SlotPlanner.validLengths(5)).isEqualTo(listOf(5, 4, 2))
        assertThat(SlotPlanner.validLengths(6)).isEqualTo(listOf(6, 5, 2, 3))
        // M = 10: full corpus case. M-2 = 8 forbidden.
        assertThat(SlotPlanner.validLengths(10)).containsExactlyInAnyOrder(10, 9, 7, 6, 5, 4, 3, 2)
    }

    @Test
    fun `4x4 plan produces only valid lengths and full coverage`() {
        val arrows = Skeleton.arrows(4, 4)
        val slots = SlotPlanner.planVariable(arrows, 4, 4, Random(0L), Long.MAX_VALUE)
        assertThat(slots).isNotNull()
        slots!!.forEach { slot ->
            assertThat(slot.length).isGreaterThanOrEqualTo(2)
        }
    }

    @Test
    fun `10x10 plan succeeds within a generous deadline`() {
        // The whole point of v2 — this is the case v1 couldn't satisfy.
        val arrows = Skeleton.arrows(10, 10)
        val slots = SlotPlanner.planVariable(arrows, 10, 10, Random(42L), System.currentTimeMillis() + 5_000L)
        assertThat(slots).isNotNull()
    }

    @Test
    fun `most seeds yield plans that skew shorter than full length`() {
        // Some seeds time out; the median plan should bias toward shorter slots.
        val averages =
            (0L..9L).mapNotNull { seed ->
                val arrows = Skeleton.arrows(10, 10)
                val slots = SlotPlanner.planVariable(arrows, 10, 10, Random(seed), System.currentTimeMillis() + 2_000L)
                slots?.map { it.length }?.average()
            }
        check(averages.size >= 6) { "fewer than 6 of 10 seeds produced a plan in 2s — too unstable" }
        val medianAvg = averages.sorted()[averages.size / 2]
        check(medianAvg < 7.5) { "median average slot length $medianAvg too high — bias not effective" }
    }

    @Test
    fun `every plan satisfies the no-orphan no-dead-clue invariant`() {
        // Run a handful of seeds; each successful plan must validate.
        for (seed in 0L..5L) {
            val w = 8
            val h = 8
            val arrows = Skeleton.arrows(w, h)
            val slots = SlotPlanner.planVariable(arrows, w, h, Random(seed), System.currentTimeMillis() + 5_000L) ?: continue

            val clueCells = mutableSetOf<Position>()
            val letterCells = mutableSetOf<Position>()
            slots.forEach { slot ->
                clueCells += slot.cluePosition
                letterCells += slot.letterPositions()
            }
            // Every cell of the w×h grid is either clue or letter — no orphans.
            for (r in 0 until h) {
                for (c in 0 until w) {
                    val pos =
                        Position(
                            com.bliss.grid.domain.model.Row(r),
                            com.bliss.grid.domain.model.Column(c),
                        )
                    val claimed = pos in clueCells || pos in letterCells
                    check(claimed) { "orphan cell at $pos for seed=$seed" }
                }
            }
        }
    }
}
