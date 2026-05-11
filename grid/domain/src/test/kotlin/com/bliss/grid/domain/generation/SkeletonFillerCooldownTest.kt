package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordClue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Cooldown-aware clue picking in [SkeletonFiller] — see ADR-0031. */
class SkeletonFillerCooldownTest {
    private fun pos(
        r: Int,
        c: Int,
    ) = Position(Row(r), Column(c))

    private val twoClueWord =
        Word(
            text = "EST",
            clues =
                listOf(
                    WordClue("Verbe etre", null),
                    WordClue("Direction cardinale", null),
                ),
        )

    private val singleSlot = WordSlot(pos(0, 0), Direction.DOWN_RIGHT, 3)

    @Test
    fun `cooldown filters one clue away when the other is fresh`() {
        val cooled = ClueId(wordText = "EST", clueText = "Verbe etre")
        val policy = ClueCooldownPolicy.fromSet(setOf(cooled))

        val placements =
            SkeletonFiller(ListWordRepository(listOf(twoClueWord)), policy)
                .fill(
                    slots = listOf(singleSlot),
                    random = Random(0),
                    deadline = System.currentTimeMillis() + 1_000,
                )

        assertThat(placements).isNotNull()
        assertThat(placements!!.single().chosenClue.text).isEqualTo("Direction cardinale")
    }

    @Test
    fun `cooldown all clues excludes the word from the search domain`() {
        val both =
            setOf(
                ClueId("EST", "Verbe etre"),
                ClueId("EST", "Direction cardinale"),
            )
        val policy = ClueCooldownPolicy.fromSet(both)

        val placements =
            SkeletonFiller(ListWordRepository(listOf(twoClueWord)), policy)
                .fill(
                    slots = listOf(singleSlot),
                    random = Random(0),
                    deadline = System.currentTimeMillis() + 1_000,
                )

        // New contract (Wave 2): a word with no usable (non-cooldown) clue is
        // excluded from the search domain — no silent fallback to a cooled clue.
        // With only this single word in the repo and all its clues on cooldown,
        // the search has no candidate and returns null. In production with a
        // 100k-word corpus, the search simply moves on to a different word.
        assertThat(placements).isNull()
    }

    @Test
    fun `Inert policy preserves existing pick behavior`() {
        val placements =
            SkeletonFiller(ListWordRepository(listOf(twoClueWord)), ClueCooldownPolicy.Inert)
                .fill(
                    slots = listOf(singleSlot),
                    random = Random(0),
                    deadline = System.currentTimeMillis() + 1_000,
                )
        assertThat(placements).isNotNull()
        assertThat(placements!!.single().chosenClue.text)
            .isIn("Verbe etre", "Direction cardinale")
    }

    @Test
    fun `default cooldown is Inert when policy not specified`() {
        val placements =
            SkeletonFiller(ListWordRepository(listOf(twoClueWord)))
                .fill(
                    slots = listOf(singleSlot),
                    random = Random(0),
                    deadline = System.currentTimeMillis() + 1_000,
                )
        assertThat(placements).isNotNull()
        assertThat(placements!!.single().chosenClue.text)
            .isIn("Verbe etre", "Direction cardinale")
    }
}
