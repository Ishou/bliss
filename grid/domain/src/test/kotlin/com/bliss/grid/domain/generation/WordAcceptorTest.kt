package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordClue
import kotlin.random.Random
import org.junit.jupiter.api.Test

class WordAcceptorTest {
    @Test
    fun `accepts returns true on a fresh word with non-themed clue`() {
        val acceptor = WordAcceptor(themeLimits = emptyMap(), cooldownPolicy = ClueCooldownPolicy.Inert)
        val word = Word(text = "MOTS", clues = listOf(WordClue("Paroles")))
        assertThat(acceptor.accepts(word)).isTrue()
    }

    @Test
    fun `accepts returns false on already-placed surface form`() {
        val acceptor = WordAcceptor(themeLimits = emptyMap(), cooldownPolicy = ClueCooldownPolicy.Inert)
        val word = Word(text = "MOTS", clues = listOf(WordClue("Paroles")))
        val clue = acceptor.pickClue(word, Random(0))!!
        acceptor.recordPlacement(word, clue)
        assertThat(acceptor.accepts(word)).isFalse()
    }

    @Test
    fun `accepts returns false on already-placed lemma`() {
        val acceptor = WordAcceptor(themeLimits = emptyMap(), cooldownPolicy = ClueCooldownPolicy.Inert)
        val ours = Word(text = "COURT", lemma = "COURIR", clues = listOf(WordClue("c1")))
        val courait = Word(text = "COURAIT", lemma = "COURIR", clues = listOf(WordClue("c2")))
        val clue = acceptor.pickClue(ours, Random(0))!!
        acceptor.recordPlacement(ours, clue)
        assertThat(acceptor.accepts(courait)).isFalse()
    }

    @Test
    fun `theme cap blocks further placements once met`() {
        val themedClue = WordClue("Direction", theme = "compass")
        val w1 = Word(text = "EST", clues = listOf(themedClue))
        val w2 = Word(text = "OUEST", clues = listOf(themedClue))
        val acceptor = WordAcceptor(themeLimits = mapOf("compass" to 1), cooldownPolicy = ClueCooldownPolicy.Inert)
        val clue = acceptor.pickClue(w1, Random(0))!!
        acceptor.recordPlacement(w1, clue)
        // w2's only clue is themed compass; cap is 1 and already used.
        assertThat(acceptor.accepts(w2)).isFalse()
    }

    @Test
    fun `pickClue returns null when no clue is usable due to cooldown`() {
        val word = Word(text = "EST", clues = listOf(WordClue("verb"), WordClue("compass", theme = "compass")))
        val policy = ClueCooldownPolicy.fromSet(setOf(ClueId("EST", "verb"), ClueId("EST", "compass")))
        val acceptor = WordAcceptor(themeLimits = emptyMap(), cooldownPolicy = policy)
        assertThat(acceptor.pickClue(word, Random(0))).isNull()
    }

    @Test
    fun `pickClue prefers non-themed clue`() {
        val word =
            Word(
                text = "EST",
                clues = listOf(WordClue("compass", theme = "compass"), WordClue("verb")),
            )
        val acceptor = WordAcceptor(themeLimits = emptyMap(), cooldownPolicy = ClueCooldownPolicy.Inert)
        repeat(20) {
            val clue = acceptor.pickClue(word, Random(it.toLong()))!!
            assertThat(clue.text).isEqualTo("verb") // always the non-themed one
        }
    }

    @Test
    fun `removePlacement undoes recordPlacement`() {
        val word = Word(text = "MOTS", clues = listOf(WordClue("Paroles")))
        val acceptor = WordAcceptor(themeLimits = emptyMap(), cooldownPolicy = ClueCooldownPolicy.Inert)
        val clue = acceptor.pickClue(word, Random(0))!!
        acceptor.recordPlacement(word, clue)
        assertThat(acceptor.accepts(word)).isFalse()
        acceptor.removePlacement(word, clue)
        assertThat(acceptor.accepts(word)).isTrue()
    }

    @Test
    fun `themeUsedView reflects theme placements`() {
        val themedClue = WordClue("Direction", theme = "compass")
        val word = Word(text = "EST", clues = listOf(themedClue))
        val acceptor = WordAcceptor(themeLimits = mapOf("compass" to 5), cooldownPolicy = ClueCooldownPolicy.Inert)
        acceptor.recordPlacement(word, acceptor.pickClue(word, Random(0))!!)
        assertThat(acceptor.themeUsedView["compass"]).isEqualTo(1)
    }
}
