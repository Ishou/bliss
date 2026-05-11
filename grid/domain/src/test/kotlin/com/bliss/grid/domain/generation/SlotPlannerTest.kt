package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import org.junit.jupiter.api.Test

class SlotPlannerTest {
    @Test
    fun `validLengths returns descending so the search tries longer lengths first`() {
        // M=10 → [10, 9, 7, 6, 5, 4, 3, 2] (M-2=8 forbidden, rest descending)
        assertThat(SlotPlanner.validLengths(10)).containsExactly(10, 9, 7, 6, 5, 4, 3, 2)
    }

    @Test
    fun `validLengths for small M only includes the full length and M-1`() {
        assertThat(SlotPlanner.validLengths(2)).containsExactly(2)
        assertThat(SlotPlanner.validLengths(3)).containsExactly(3, 2)
        assertThat(SlotPlanner.validLengths(4)).containsExactly(4, 3)
        // M=5 → [5, 4, 2] — M-3 = 2 included, M-2 = 3 forbidden
        assertThat(SlotPlanner.validLengths(5)).containsExactly(5, 4, 2)
    }

    @Test
    fun `corpusAwareLengthPolicy filters lengths below the corpus floor, preserving descending order`() {
        val repo =
            object : WordRepository {
                override fun findByLength(length: Int): List<Word> = emptyList()

                override fun findByLengthAndPattern(
                    length: Int,
                    pattern: Map<Int, Char>,
                ): List<Word> = emptyList()

                override fun countByLength(length: Int): Int = if (length <= 5) 1000 else 10

                override fun containsLemma(text: String): Boolean = false
            }

        val policy = SlotPlanner.corpusAwareLengthPolicy(repo, minCorpus = 100)
        // M=10 → validLengths = [10, 9, 7, 6, 5, 4, 3, 2]. minCorpus=100 keeps
        // only lengths ≤ 5, preserving descending order: [5, 4, 3, 2].
        assertThat(policy(10)).containsExactly(5, 4, 3, 2)
    }

    @Test
    fun `corpusAwareLengthPolicy falls back to validLengths when filtering wipes everything`() {
        val sparseRepo =
            object : WordRepository {
                override fun findByLength(length: Int): List<Word> = emptyList()

                override fun findByLengthAndPattern(
                    length: Int,
                    pattern: Map<Int, Char>,
                ): List<Word> = emptyList()

                override fun countByLength(length: Int): Int = 0 // nothing meets the floor

                override fun containsLemma(text: String): Boolean = false
            }

        val policy = SlotPlanner.corpusAwareLengthPolicy(sparseRepo, minCorpus = 100)
        // No length has 100+ words, so fall back to the full validLengths set.
        assertThat(policy(5)).isEqualTo(SlotPlanner.validLengths(5))
    }

    @Test
    fun `orphanSafeLengths restricts (0,0) DOWN_RIGHT to full length or even trails`() {
        val arrow = ClueArrow(Position(Row(0), Column(0)), Direction.DOWN_RIGHT)
        // available = 10 → validLengths = [10, 9, 7, 6, 5, 4, 3, 2].
        // Corner restriction: keep 10 (full) or even values, preserving
        // descending order: [10, 6, 4, 2].
        val result = SlotPlanner.orphanSafeLengths(arrow, available = 10, lengthPolicy = ::dummyValidLengths)
        assertThat(result).containsExactly(10, 6, 4, 2)
    }

    @Test
    fun `orphanSafeLengths leaves non-corner arrows unchanged`() {
        val arrow = ClueArrow(Position(Row(0), Column(4)), Direction.DOWN)
        val result = SlotPlanner.orphanSafeLengths(arrow, available = 10, lengthPolicy = ::dummyValidLengths)
        // Non-corner: no filtering applied beyond what the lengthPolicy returns.
        assertThat(result).isEqualTo(SlotPlanner.validLengths(10))
    }

    private fun dummyValidLengths(available: Int): List<Int> = SlotPlanner.validLengths(available)
}
