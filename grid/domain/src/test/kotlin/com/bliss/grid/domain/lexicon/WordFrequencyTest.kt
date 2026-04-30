package com.bliss.grid.domain.lexicon

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class WordFrequencyTest {
    private fun parse(vararg lines: String) = parseFrequencies(lines.asSequence())

    @Test
    fun `returns word-count pairs for normal lines`() {
        assertThat(parse("chat 5000", "chien 4000"))
            .isEqualTo(listOf(WordFrequency("chat", 5000L), WordFrequency("chien", 4000L)))
    }

    @Test
    fun `skips comment lines`() {
        assertThat(parse("# comment", "chat 5000", "# another comment", "chien 4000"))
            .isEqualTo(listOf(WordFrequency("chat", 5000L), WordFrequency("chien", 4000L)))
    }

    @Test
    fun `skips empty lines`() {
        assertThat(parse("chat 5000", "", "   ", "chien 4000"))
            .isEqualTo(listOf(WordFrequency("chat", 5000L), WordFrequency("chien", 4000L)))
    }

    @Test
    fun `skips lines with non-numeric count`() {
        assertThat(parse("chat abc", "chien 4000"))
            .isEqualTo(listOf(WordFrequency("chien", 4000L)))
    }

    @Test
    fun `skips lines with no space separator`() {
        assertThat(parse("chatsanspace", "chien 4000"))
            .isEqualTo(listOf(WordFrequency("chien", 4000L)))
    }

    @Test
    fun `lowercases words`() {
        assertThat(parse("Chat 5000")).isEqualTo(listOf(WordFrequency("chat", 5000L)))
    }

    @Test
    fun `returns empty list for empty input`() {
        assertThat(parse()).isEmpty()
    }

    @Test
    fun `trims surrounding whitespace from lines`() {
        assertThat(parse("  chat   5000  ")).isEqualTo(listOf(WordFrequency("chat", 5000L)))
    }
}
