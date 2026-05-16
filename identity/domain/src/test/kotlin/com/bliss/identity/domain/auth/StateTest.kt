package com.bliss.identity.domain.auth

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasLength
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class StateTest {
    @Test
    fun `generate produces a 43-character url-safe base64 string`() {
        val state = State.generate(SecureRandom())
        assertThat(state.value).hasLength(43)
    }

    @Test
    fun `two generated states are different`() {
        val a = State.generate(SecureRandom())
        val b = State.generate(SecureRandom())
        assertThat(a.value).isNotEqualTo(b.value)
    }

    @Test
    fun `of accepts a valid string`() {
        val state = State.of("abc123")
        assertThat(state.value).hasLength(6)
    }

    @Test
    fun `of rejects blank`() {
        assertFailure { State.of("") }.isInstanceOf(IllegalArgumentException::class)
    }
}
