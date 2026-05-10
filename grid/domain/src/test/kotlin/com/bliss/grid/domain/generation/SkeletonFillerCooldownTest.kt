package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import assertk.assertions.isNotNull
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
    fun `cooldown all clues forces fallback to existing behavior`() {
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

        assertThat(placements).isNotNull()
        // Either clue is acceptable — the contract is "do not fail", not
        // "pick any specific clue" when nothing is fresh.
        assertThat(placements!!.single().chosenClue.text)
            .isIn("Verbe etre", "Direction cardinale")
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
        // Regression: existing call sites that don't pass a policy continue
        // to compile and behave as before (no cooldown applied).
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
