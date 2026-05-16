package com.bliss.identity.domain.auth

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasLength
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.printableAscii
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.security.SecureRandom

@OptIn(ExperimentalKotest::class)
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
    fun `property - of round-trips any token of sufficient length`() {
        runBlocking {
            checkAll(PropTestConfig(iterations = 200), Arb.string(32..200, Codepoint.printableAscii())) { raw ->
                assertThat(State.of(raw).value).isEqualTo(raw)
            }
        }
    }
}
