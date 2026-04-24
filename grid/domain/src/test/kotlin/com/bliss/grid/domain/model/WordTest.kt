package com.bliss.grid.domain.model

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class WordTest {
    @Test
    fun `Word holds uppercase text and definition`() {
        val w = Word("CHAT", "felin domestique")
        assertThat(w.text).isEqualTo("CHAT")
        assertThat(w.definition).isEqualTo("felin domestique")
    }

    @Test
    fun `Word uppercases lowercase input`() {
        assertThat(Word("chat", "x").text).isEqualTo("CHAT")
    }

    @Test
    fun `Word uppercases mixed-case input`() {
        assertThat(Word("ChAt", "x").text).isEqualTo("CHAT")
    }

    @Test
    fun `Word rejects empty text`() {
        assertFailure { Word("", "x") }
    }

    @Test
    fun `Word rejects non-alphabetic characters`() {
        assertFailure { Word("CH1T", "x") }
    }

    @Test
    fun `Word rejects whitespace`() {
        assertFailure { Word("CH AT", "x") }
    }

    @Test
    fun `Word equality is structural`() {
        assertThat(Word("CHAT", "x")).isEqualTo(Word("chat", "x"))
    }
}
