package com.bliss.identity.domain.auth

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasLength
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class StateTest {
    private val rng = SecureRandom()

    @Test
    fun `generate produces a 43-character url-safe base64 string`() {
        val state = State.generate(rng)
        assertThat(state.value).hasLength(43)
    }

    @Test
    fun `two generated states are different`() {
        val a = State.generate(rng)
        val b = State.generate(rng)
        assertThat(a.value).isNotEqualTo(b.value)
    }

    @Test
    fun `of accepts a string of at least 32 characters`() {
        val raw = "a".repeat(32)
        assertThat(State.of(raw).value).hasLength(32)
    }

    @Test
    fun `of rejects blank`() {
        assertFailure { State.of("") }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `of rejects a string shorter than 32 characters`() {
        assertFailure { State.of("a".repeat(31)) }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `property - every generated state is 43 base64url characters without padding`() {
        val base64Url = Regex("^[A-Za-z0-9_-]+$")
        repeat(200) {
            val state = State.generate(rng)
            assertThat(state.value).hasLength(43)
            assertThat(base64Url.matches(state.value)).isTrue()
        }
    }
}
