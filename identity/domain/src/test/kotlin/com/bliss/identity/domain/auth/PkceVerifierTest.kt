package com.bliss.identity.domain.auth

import assertk.assertThat
import assertk.assertions.hasLength
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class PkceVerifierTest {
    @Test
    fun `generate produces a 43-character url-safe base64 verifier`() {
        val v = PkceVerifier.generate(SecureRandom())
        assertThat(v.value).hasLength(43)
    }

    @Test
    fun `challenge is the base64-url SHA256 of the verifier`() {
        val v = PkceVerifier.of("abc")
        val expected =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest("abc".toByteArray(Charsets.US_ASCII)),
            )
        assertThat(v.challenge()).isEqualTo(expected)
    }

    @Test
    fun `two generated verifiers are different`() {
        val a = PkceVerifier.generate(SecureRandom())
        val b = PkceVerifier.generate(SecureRandom())
        assertThat(a.value).isNotEqualTo(b.value)
    }
}
