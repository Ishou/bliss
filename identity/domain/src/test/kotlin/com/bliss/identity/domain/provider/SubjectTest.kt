package com.bliss.identity.domain.provider

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class SubjectTest {
    @Test
    fun `Provider has google and apple variants`() {
        assertThat(Provider.entries.toSet()).isEqualTo(setOf(Provider.GOOGLE, Provider.APPLE))
    }

    @Test
    fun `Provider parses lowercase wire form`() {
        assertThat(Provider.fromWire("google")).isEqualTo(Provider.GOOGLE)
        assertThat(Provider.fromWire("apple")).isEqualTo(Provider.APPLE)
    }

    @Test
    fun `Provider rejects unknown wire form`() {
        assertFailure { Provider.fromWire("facebook") }
            .isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `Subject preserves the raw IdP-issued string`() {
        assertThat(Subject.of("12345-google-sub").value).isEqualTo("12345-google-sub")
    }

    @Test
    fun `Subject rejects blank`() {
        assertFailure { Subject.of("") }
            .isInstanceOf(IllegalArgumentException::class)
        assertFailure { Subject.of("  ") }
            .isInstanceOf(IllegalArgumentException::class)
    }
}
