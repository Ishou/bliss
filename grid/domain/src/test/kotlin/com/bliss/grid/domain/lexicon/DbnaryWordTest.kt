package com.bliss.grid.domain.lexicon

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DbnaryWordTest {
    @Test
    fun `accepts a minimal valid entry`() {
        val word = DbnaryWord(lemma = "voiture", pos = "noun")
        assertThat(word.language).isEqualTo("fr")
        assertThat(word.senses).isEqualTo(emptyList())
        assertThat(word.synonyms).isEqualTo(emptyList())
    }

    @Test
    fun `rejects blank lemma`() {
        assertThrows<IllegalArgumentException> { DbnaryWord(lemma = "", pos = "noun") }
        assertThrows<IllegalArgumentException> { DbnaryWord(lemma = "   ", pos = "noun") }
    }

    @Test
    fun `rejects blank pos`() {
        assertThrows<IllegalArgumentException> { DbnaryWord(lemma = "voiture", pos = "") }
    }

    @Test
    fun `rejects blank language`() {
        assertThrows<IllegalArgumentException> {
            DbnaryWord(lemma = "voiture", pos = "noun", language = "")
        }
    }

    @Test
    fun `rejects duplicate senseIndex`() {
        val s1 = DbnarySense(senseIndex = 0, definitionText = "Premier sens.")
        val s2 = DbnarySense(senseIndex = 0, definitionText = "Doublon.")
        assertThrows<IllegalArgumentException> {
            DbnaryWord(lemma = "x", pos = "noun", senses = listOf(s1, s2))
        }
    }

    @Test
    fun `rejects duplicate synonyms`() {
        assertThrows<IllegalArgumentException> {
            DbnaryWord(lemma = "x", pos = "noun", synonyms = listOf("a", "a"))
        }
    }

    @Test
    fun `accepts senses sparse and out-of-order`() {
        val word =
            DbnaryWord(
                lemma = "manger",
                pos = "verb",
                senses =
                    listOf(
                        DbnarySense(senseIndex = 2, definitionText = "Sens trois."),
                        DbnarySense(senseIndex = 0, definitionText = "Sens un."),
                    ),
            )
        assertThat(word.senses.size).isEqualTo(2)
    }
}

class DbnarySenseTest {
    @Test
    fun `accepts a minimal sense`() {
        val sense = DbnarySense(senseIndex = 0, definitionText = "Une définition.")
        assertThat(sense.register).isEqualTo(null)
    }

    @Test
    fun `rejects negative senseIndex`() {
        assertThrows<IllegalArgumentException> {
            DbnarySense(senseIndex = -1, definitionText = "x")
        }
    }

    @Test
    fun `rejects blank definitionText`() {
        assertThrows<IllegalArgumentException> {
            DbnarySense(senseIndex = 0, definitionText = "   ")
        }
    }

    @Test
    fun `rejects blank register when provided`() {
        assertThrows<IllegalArgumentException> {
            DbnarySense(senseIndex = 0, definitionText = "x", register = "  ")
        }
    }

    @Test
    fun `accepts register marker`() {
        val sense =
            DbnarySense(
                senseIndex = 1,
                definitionText = "Bagnole.",
                register = "Familier",
            )
        assertThat(sense.register).isEqualTo("Familier")
    }
}
