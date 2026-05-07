package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Theme-cap mechanics: words tagged with a theme over the per-grid cap
 * must be rejected by the filler; the search must still complete when
 * a non-themed alternative exists.
 *
 * Uses tiny hand-built slot graphs and the in-memory [ListWordRepository]
 * so we can pin the exact word set seen by the search.
 */
class SkeletonFillerThemeTest {
    private fun pos(
        r: Int,
        c: Int,
    ) = Position(Row(r), Column(c))

    /**
     * Two parallel slots, no intersection. Three candidate words —
     * two share theme `chem`, one is unthemed. Cap = 1 chem; the second
     * slot must pick the unthemed candidate.
     */
    @Test
    fun `cap of 1 forces the second slot off the themed candidate`() {
        // Two non-intersecting parallel slots so the candidates are independent.
        val slotA = WordSlot(pos(0, 0), Direction.DOWN_RIGHT, 2)
        val slotB = WordSlot(pos(2, 0), Direction.DOWN_RIGHT, 2)

        val themed1 = Word(text = "FE", definition = "Symbole du fer", theme = "chem")
        val themed2 = Word(text = "AU", definition = "Symbole de l'or", theme = "chem")
        val plain = Word(text = "DE", definition = "Preposition")

        val filler = SkeletonFiller(ListWordRepository(listOf(themed1, themed2, plain)))
        val placements =
            filler.fill(
                slots = listOf(slotA, slotB),
                random = Random(0),
                deadline = System.currentTimeMillis() + 1_000,
                themeLimits = mapOf("chem" to 1),
            )
        assertNotNull(placements, "expected a fill")
        val themesPicked = placements!!.map { it.word.theme }.filter { it != null }
        assertEquals(1, themesPicked.size, "exactly one chem-themed word should be placed")
    }

    /** Cap = 0 bans a theme outright — fill must fail if all candidates are themed. */
    @Test
    fun `cap of 0 with only themed candidates fails the fill`() {
        val slot = WordSlot(pos(0, 0), Direction.DOWN_RIGHT, 2)
        val themedOnly =
            listOf(
                Word(text = "FE", definition = "Symbole du fer", theme = "chem"),
                Word(text = "AU", definition = "Symbole de l'or", theme = "chem"),
            )
        val filler = SkeletonFiller(ListWordRepository(themedOnly))
        val placements =
            filler.fill(
                slots = listOf(slot),
                random = Random(0),
                deadline = System.currentTimeMillis() + 500,
                themeLimits = mapOf("chem" to 0),
            )
        assertNull(placements, "all candidates banned → fill should fail")
    }

    /** Theme not in the limits map means uncapped. */
    @Test
    fun `theme without an entry in limits map is uncapped`() {
        val slotA = WordSlot(pos(0, 0), Direction.DOWN_RIGHT, 2)
        val slotB = WordSlot(pos(2, 0), Direction.DOWN_RIGHT, 2)
        val both =
            listOf(
                Word(text = "FE", definition = "fer", theme = "chem"),
                Word(text = "AU", definition = "or", theme = "chem"),
            )
        val filler = SkeletonFiller(ListWordRepository(both))
        val placements =
            filler.fill(
                slots = listOf(slotA, slotB),
                random = Random(0),
                deadline = System.currentTimeMillis() + 500,
                themeLimits = mapOf("country" to 1), // unrelated theme
            )
        assertNotNull(placements, "chem uncapped → both themed slots should fill")
    }

    /** Backtracking still resets theme counters cleanly. */
    @Test
    fun `theme counter symmetric across backtracks`() {
        // Two slots; the first attempted word would over-cap so the search
        // must back off and pick the alternative. A successful run proves
        // both placement and undo paths track the counter correctly.
        val slotA = WordSlot(pos(0, 0), Direction.DOWN_RIGHT, 2)
        val slotB = WordSlot(pos(2, 0), Direction.DOWN_RIGHT, 2)
        val candidates =
            listOf(
                Word(text = "FE", definition = "fer", theme = "chem"),
                Word(text = "AU", definition = "or", theme = "chem"),
                Word(text = "ON", definition = "pronom"),
            )
        val filler = SkeletonFiller(ListWordRepository(candidates))
        val placements =
            filler.fill(
                slots = listOf(slotA, slotB),
                random = Random(0),
                deadline = System.currentTimeMillis() + 1_000,
                themeLimits = mapOf("chem" to 1),
            )
        assertNotNull(placements)
        val themesPicked = placements!!.map { it.word.theme }
        // One placement carries chem theme; the other is null (the unthemed pronom).
        assertEquals(1, themesPicked.count { it == "chem" })
        assertEquals(1, themesPicked.count { it == null })
    }
}
