package com.bliss.identity.domain.user

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class DisplayNameTest {
    @Test
    fun `accepts a 1-character name`() {
        assertThat(DisplayName.of("A").value).isEqualTo("A")
    }

    @Test
    fun `accepts a 30-character name`() {
        val name = "A".repeat(30)
        assertThat(DisplayName.of(name).value).isEqualTo(name)
    }

    @Test
    fun `rejects an empty name`() {
        assertFailure { DisplayName.of("") }
            .isInstanceOf(IllegalArgumentException::class)
            .hasMessage("Display name must be 1-30 characters; got 0.")
    }

    @Test
    fun `rejects a 31-character name`() {
        val name = "A".repeat(31)
        assertFailure { DisplayName.of(name) }
            .isInstanceOf(IllegalArgumentException::class)
            .hasMessage("Display name must be 1-30 characters; got 31.")
    }

    @Test
    fun `trims surrounding whitespace before validating`() {
        assertThat(DisplayName.of("  Alice  ").value).isEqualTo("Alice")
    }

    @Test
    fun `rejects a whitespace-only name`() {
        assertFailure { DisplayName.of("    ") }
            .isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `property - any non-empty trimmed string up to 30 chars is accepted`() {
        runBlocking {
            checkAll(Arb.string(minSize = 1, maxSize = 30)) { raw ->
                val trimmed = raw.trim()
                if (trimmed.isNotEmpty() && trimmed.length <= 30) {
                    assertThat(DisplayName.of(trimmed).value).isEqualTo(trimmed)
                }
            }
        }
    }
}
