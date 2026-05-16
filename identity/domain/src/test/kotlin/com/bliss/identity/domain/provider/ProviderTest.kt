package com.bliss.identity.domain.provider

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class ProviderTest {
    @Test
    fun `Provider has google and apple variants`() {
        assertThat(Provider.entries.toSet()).isEqualTo(setOf(Provider.GOOGLE, Provider.APPLE))
    }
}
