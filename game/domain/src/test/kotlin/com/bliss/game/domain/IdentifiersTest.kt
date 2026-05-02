package com.bliss.game.domain

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasLength
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import assertk.assertions.matches
import assertk.assertions.messageContains
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

@OptIn(ExperimentalKotest::class)
class IdentifiersTest {
    private val base58 = Regex("^[1-9A-HJ-NP-Za-km-z]{8}$")

    @Test
    fun `LobbyId accepts an 8-char base58 value`() {
        assertThat(LobbyId("7gQ2xK9p").value).isEqualTo("7gQ2xK9p")
    }

    @Test
    fun `LobbyId rejects wrong length`() {
        assertFailure { LobbyId("7gQ2xK9") }.messageContains("base58")
        assertFailure { LobbyId("7gQ2xK9p1") }.messageContains("base58")
    }

    @Test
    fun `LobbyId rejects ambiguous base58 chars`() {
        // 0, O, I and l are excluded from base58.
        assertFailure { LobbyId("0gQ2xK9p") }.messageContains("base58")
        assertFailure { LobbyId("OgQ2xK9p") }.messageContains("base58")
        assertFailure { LobbyId("IgQ2xK9p") }.messageContains("base58")
        assertFailure { LobbyId("lgQ2xK9p") }.messageContains("base58")
    }

    @Test
    fun `LobbyId generate always produces a valid id`() {
        runBlocking {
            checkAll(PropTestConfig(iterations = 200), Arb.string()) { _ ->
                val id = LobbyId.generate()
                assertThat(id.value).hasLength(8)
                assertThat(base58.matches(id.value)).isTrue()
            }
        }
    }

    @Test
    fun `SessionId accepts a UUID string`() {
        assertThat(SessionId("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b").value)
            .isEqualTo("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b")
    }

    @Test
    fun `SessionId rejects non-UUID strings`() {
        assertFailure { SessionId("not-a-uuid") }.messageContains("UUID")
        assertFailure { SessionId("") }.messageContains("UUID")
    }

    @Test
    fun `Pseudonym accepts a 1-32 char trimmed name`() {
        assertThat(Pseudonym("Alice").value).isEqualTo("Alice")
        assertThat(Pseudonym("A").value).isEqualTo("A")
        assertThat(Pseudonym("a".repeat(32)).value).hasLength(32)
    }

    @Test
    fun `Pseudonym rejects empty`() {
        assertFailure { Pseudonym("") }.messageContains("empty")
    }

    @Test
    fun `Pseudonym rejects leading or trailing whitespace`() {
        assertFailure { Pseudonym(" Alice") }.messageContains("whitespace")
        assertFailure { Pseudonym("Alice ") }.messageContains("whitespace")
    }

    @Test
    fun `Pseudonym rejects values longer than 32 chars`() {
        assertFailure { Pseudonym("a".repeat(33)) }.messageContains("32")
    }

    @Test
    fun `Pseudonym of trims whitespace before validating`() {
        assertThat(Pseudonym.of("  Alice  ").value).isEqualTo("Alice")
    }

    @Test
    fun `Pseudonym of rejects fully whitespace input`() {
        assertFailure { Pseudonym.of("   ") }.messageContains("empty")
    }

    @Test
    fun `Pseudonym property - of always rejects whitespace-only or yields a trimmed value`() {
        runBlocking {
            checkAll(PropTestConfig(iterations = 200), Arb.string(0..40)) { raw ->
                val trimmed = raw.trim()
                if (trimmed.isEmpty() || trimmed.length > 32) {
                    assertFailure { Pseudonym.of(raw) }
                } else {
                    val p = Pseudonym.of(raw)
                    assertThat(p.value).isEqualTo(trimmed)
                    assertThat(p.value).matches(Regex("^\\S(.*\\S)?$"))
                }
            }
        }
    }

    @Test
    fun `Letter accepts uppercase A-Z`() {
        assertThat(Letter('A').value).isEqualTo('A')
        assertThat(Letter('Z').value).isEqualTo('Z')
    }

    @Test
    fun `Letter rejects lowercase`() {
        assertFailure { Letter('a') }.messageContains("uppercase")
    }

    @Test
    fun `Letter rejects digits and punctuation`() {
        assertFailure { Letter('1') }.messageContains("uppercase")
        assertFailure { Letter('-') }.messageContains("uppercase")
    }
}
