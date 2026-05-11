package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordClue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class IntegratedSearchTest {
    private val future: Long get() = Long.MAX_VALUE

    private fun buildState(
        width: Int,
        height: Int,
    ): PlanState {
        val state = PlanState(width, height)
        for (arrow in Skeleton.arrows(width, height)) {
            state.addClueCell(arrow.cluePosition)
            state.addArrow(arrow.cluePosition, arrow.direction)
        }
        return state
    }

    /** Synthesises a deterministic word for every (length, pattern). */
    private object AnyLetterRepository : WordRepository {
        override fun findByLength(length: Int): List<Word> = emptyList()

        override fun findByLengthAndPattern(
            length: Int,
            pattern: Map<Int, Char>,
        ): List<Word> {
            val freePos = (0 until length).firstOrNull { it !in pattern } ?: return listOf(synth(length, pattern))
            return ('A'..'Z').map { ch ->
                val full = StringBuilder("X".repeat(length))
                for ((i, p) in pattern) full[i] = p
                full[freePos] = ch
                Word(text = full.toString(), lemma = full.toString(), clues = listOf(WordClue(text = "clue-$full")))
            }
        }

        private fun synth(
            length: Int,
            pattern: Map<Int, Char>,
        ): Word {
            val full = StringBuilder("X".repeat(length))
            for ((i, p) in pattern) full[i] = p
            return Word(text = full.toString(), lemma = full.toString(), clues = listOf(WordClue(text = "clue-$full")))
        }

        override fun countByLength(length: Int): Int = 26

        override fun containsLemma(text: String): Boolean = true
    }

    @Test
    fun `solve fills a tiny grid using a permissive repository`() {
        val state = buildState(width = 5, height = 5)
        val search =
            IntegratedSearch(
                repository = AnyLetterRepository,
                cooldownPolicy = ClueCooldownPolicy.Inert,
                clock = SystemClock,
                lengthPolicy = SlotPlanner::validLengths,
            )

        val ok = search.solve(state, Random(0), deadline = future, themeLimits = emptyMap())

        assertThat(ok).isTrue()
        assertThat(state.placements).isNotNull()
        assertThat(state.placements.size).isEqualTo(state.slots.size)
    }

    @Test
    fun `solve returns false when deadline is already in the past`() {
        val state = buildState(width = 5, height = 5)
        val search =
            IntegratedSearch(
                repository = AnyLetterRepository,
                cooldownPolicy = ClueCooldownPolicy.Inert,
                clock = SystemClock,
                lengthPolicy = SlotPlanner::validLengths,
            )

        val ok = search.solve(state, Random(0), deadline = 0L, themeLimits = emptyMap())

        assertThat(ok).isFalse()
    }

    @Test
    fun `solve respects theme caps`() {
        val compassRepo =
            object : WordRepository {
                override fun findByLength(length: Int): List<Word> = emptyList()

                override fun findByLengthAndPattern(
                    length: Int,
                    pattern: Map<Int, Char>,
                ): List<Word> {
                    val full = StringBuilder("X".repeat(length))
                    for ((i, p) in pattern) full[i] = p
                    return listOf(
                        Word(
                            text = full.toString(),
                            lemma = full.toString(),
                            clues = listOf(WordClue(text = "Direction", theme = "compass")),
                        ),
                    )
                }

                override fun countByLength(length: Int): Int = 1

                override fun containsLemma(text: String): Boolean = true
            }

        val state = buildState(width = 5, height = 5)
        val search =
            IntegratedSearch(
                repository = compassRepo,
                cooldownPolicy = ClueCooldownPolicy.Inert,
                clock = SystemClock,
                lengthPolicy = SlotPlanner::validLengths,
            )

        // Cap of 0 → no compass clue may be placed → search fails to commit anything.
        val ok = search.solve(state, Random(0), deadline = future, themeLimits = mapOf("compass" to 0))

        assertThat(ok).isFalse()
    }
}
