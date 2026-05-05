package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
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

    @Test
    fun `usedLemmas is restored on backtrack so freed lemmas are available to later candidates`() {
        // Geometry: Slot A RIGHT-4 at (1,0), Slot B DOWN-3 at (0,2); shared cell (1,2)
        // is index 1 of Slot A and index 0 of Slot B.
        //
        // MRV picks Slot B first (domain 2 < Slot A domain 3). headShuffle with seed 0
        // reorders [ARS, ORS] → [ORS, ARS], so ORS is tried first:
        //   • Slot B = ORS (lemma VER): O at (1,2), usedLemmas = {VER}
        //   • Slot A domain filtered to O-at-index-1 → {ROSE (lemma VER)} — blocked → empty → backtrack
        //   • undo ORS: O removed, VER removed from usedLemmas   ← line under test
        //   • Slot B = ARS (lemma NOM): A at (1,2), usedLemmas = {NOM}
        //   • Slot A domain filtered to A-at-index-1 → {RAZE (lemma VER)} — VER free → valid → success
        //
        // Without the undo, VER leaks and RAZE is blocked on the second try, giving null.
        val filler =
            SkeletonFiller(
                ListWordRepository(
                    listOf(
                        // 3-letter Slot B candidates — ARS listed first so shuffle puts ORS first
                        Word("ARS", "", lemma = "NOM"),
                        Word("ORS", "", lemma = "VER"),
                        // 4-letter Slot A candidates
                        Word("ROSE", "", lemma = "VER"), // O at index 1
                        Word("RAZE", "", lemma = "VER"), // A at index 1
                        Word("RUSE", "", lemma = "ADJ"), // U at index 1 — no shared-cell match
                    ),
                ),
            )
        val slots =
            listOf(
                WordSlot(pos(1, 0), Direction.RIGHT, length = 4),
                WordSlot(pos(0, 2), Direction.DOWN, length = 3),
            )
        assertThat(filler.fill(slots, random, future)).isNotNull()
    }

    @Test
    fun `excludes inflected forms of an already-placed lemma`() {
        // Two independent length-3 slots. The corpus carries two distinct surface
        // forms ("OUI" and "OUS") that share the same lemma "OUI" — placing one
        // must forbid the other on the second slot. SLG (a third unrelated word)
        // is the only legal completion when lemma-dedup kicks in.
        val filler =
            SkeletonFiller(
                ListWordRepository(
                    listOf(
                        Word("OUI", "", lemma = "OUI"),
                        Word("OUS", "", lemma = "OUI"),
                        Word("SLG", "", lemma = "SLG"),
                    ),
                ),
            )
        val slots =
            listOf(
                WordSlot(pos(0, 0), Direction.RIGHT, length = 3),
                WordSlot(pos(2, 0), Direction.RIGHT, length = 3),
            )
        val result = filler.fill(slots, random, future)
        assertThat(result).isNotNull()
        val lemmas = result!!.map { it.word.lemma }.toSet()
        // Two distinct lemmas across the two placements — never two from "OUI".
        assertThat(lemmas.size).isEqualTo(2)
    }

    @Test
    fun `stacked slot only accepts compact words`() {
        // Two slots sharing the same cluePosition → stackedCluePositions contains
        // that position → domainFor filters to compact == true only.
        val filler =
            SkeletonFiller(
                ListWordRepository(
                    listOf(
                        Word("AIR", "Court", compact = true),
                        Word("ARC", "Long clue that overflows", compact = false),
                        Word("AS", "Carte", compact = true),
                        Word("OR", "Non compact", compact = false),
                    ),
                ),
            )
        val slots =
            listOf(
                WordSlot(pos(0, 0), Direction.RIGHT, length = 3),
                WordSlot(pos(0, 0), Direction.DOWN, length = 2),
            )
        val result = filler.fill(slots, random, future)
        assertThat(result).isNotNull()
        result!!.forEach { assertThat(it.word.compact).isTrue() }
    }

    @Test
    fun `returns null when no compact words exist for stacked slot`() {
        // Same stacked geometry but every word in the corpus is compact = false,
        // so domainFor returns an empty list for every slot → search returns false.
        val filler =
            SkeletonFiller(
                ListWordRepository(
                    listOf(
                        Word("AIR", "Long clue", compact = false),
                        Word("AS", "Also long", compact = false),
                    ),
                ),
            )
        val slots =
            listOf(
                WordSlot(pos(0, 0), Direction.RIGHT, length = 3),
                WordSlot(pos(0, 0), Direction.DOWN, length = 2),
            )
        assertThat(filler.fill(slots, random, future)).isNull()
    }

    private fun pos(
        row: Int,
        col: Int,
    ) = Position(Row(row), Column(col))
}
