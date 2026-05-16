package com.bliss.identity.infrastructure.provider

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.bliss.identity.domain.provider.Provider
import org.junit.jupiter.api.Test

class ProviderMapperTest {
    @Test
    fun `toWire returns lowercase name`() {
        assertThat(Provider.GOOGLE.toWire()).isEqualTo("google")
        assertThat(Provider.APPLE.toWire()).isEqualTo("apple")
    }

    @Test
    fun `toProvider parses lowercase wire form`() {
        assertThat("google".toProvider()).isEqualTo(Provider.GOOGLE)
        assertThat("apple".toProvider()).isEqualTo(Provider.APPLE)
    }

    @Test
    fun `toProvider rejects unknown wire form`() {
        assertFailure { "facebook".toProvider() }
            .isInstanceOf(IllegalArgumentException::class)
    }
}
