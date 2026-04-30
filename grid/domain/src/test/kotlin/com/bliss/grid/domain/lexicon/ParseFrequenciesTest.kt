package com.bliss.grid.domain.lexicon

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class ParseFrequenciesTest {
    @Test
    fun `returns word-count pairs for normal lines`() {
        val result = parseFrequencies(sequenceOf("chat 5000", "chien 4000"))
        assertThat(result).isEqualTo(listOf(WordFrequency("chat", 5000L), WordFrequency("chien", 4000L)))
    }

    @Test
    fun `skips comment lines`() {
        val result = parseFrequencies(sequenceOf("# comment", "chat 5000", "# another", "chien 4000"))
        assertThat(result).isEqualTo(listOf(WordFrequency("chat", 5000L), WordFrequency("chien", 4000L)))
    }

    @Test
    fun `skips empty and blank lines`() {
        val result = parseFrequencies(sequenceOf("chat 5000", "", "   ", "chien 4000"))
        assertThat(result).isEqualTo(listOf(WordFrequency("chat", 5000L), WordFrequency("chien", 4000L)))
    }

    @Test
    fun `skips lines with non-numeric count`() {
        val result = parseFrequencies(sequenceOf("chat abc", "chien 4000"))
        assertThat(result).isEqualTo(listOf(WordFrequency("chien", 4000L)))
    }

    @Test
    fun `skips lines with no space separator`() {
        val result = parseFrequencies(sequenceOf("chatsanspace", "chien 4000"))
        assertThat(result).isEqualTo(listOf(WordFrequency("chien", 4000L)))
    }

    @Test
    fun `lowercases words`() {
        val result = parseFrequencies(sequenceOf("Chat 5000"))
        assertThat(result).isEqualTo(listOf(WordFrequency("chat", 5000L)))
    }

    @Test
    fun `returns empty list for empty input`() {
        assertThat(parseFrequencies(emptySequence())).isEmpty()
    }
}
