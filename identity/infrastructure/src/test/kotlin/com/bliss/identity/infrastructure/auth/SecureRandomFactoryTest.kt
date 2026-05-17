package com.bliss.identity.infrastructure.auth

import assertk.assertThat
import assertk.assertions.hasLength
import assertk.assertions.isNotEqualTo
import org.junit.jupiter.api.Test

class SecureRandomFactoryTest {
    @Test
    fun `newState returns a 43-character State`() {
        val state = SecureRandomFactory().newState()
        assertThat(state.value).hasLength(43)
    }

    @Test
    fun `newPkceVerifier returns a 43-character PkceVerifier`() {
        val verifier = SecureRandomFactory().newPkceVerifier()
        assertThat(verifier.value).hasLength(43)
    }

    @Test
    fun `two newStates differ`() {
        val factory = SecureRandomFactory()
        assertThat(factory.newState().value).isNotEqualTo(factory.newState().value)
    }

    @Test
    fun `two newPkceVerifiers differ`() {
        val factory = SecureRandomFactory()
        assertThat(factory.newPkceVerifier().value).isNotEqualTo(factory.newPkceVerifier().value)
    }
}
