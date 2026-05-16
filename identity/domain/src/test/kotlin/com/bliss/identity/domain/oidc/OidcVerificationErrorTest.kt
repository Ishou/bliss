package com.bliss.identity.domain.oidc

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import org.junit.jupiter.api.Test

class OidcVerificationErrorTest {
    @Test
    fun `JwksUnavailable preserves the cause`() {
        val cause = RuntimeException("network unreachable")
        val error = OidcVerificationError.JwksUnavailable(cause)
        assertThat(error.cause).isSameInstanceAs(cause)
    }

    @Test
    fun `Malformed preserves the cause`() {
        val cause = RuntimeException("not a JWT")
        val error = OidcVerificationError.Malformed(cause)
        assertThat(error.cause).isSameInstanceAs(cause)
    }

    @Test
    fun `errors without explicit cause have null cause`() {
        assertThat(OidcVerificationError.InvalidSignature().cause).isEqualTo(null)
        assertThat(OidcVerificationError.MissingSubject().cause).isEqualTo(null)
    }
}
