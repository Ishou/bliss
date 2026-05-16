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
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@OptIn(ExperimentalKotest::class)
class PkceVerifierTest {
    private val rng = SecureRandom()

    @Test
    fun `generate produces a 43-character url-safe base64 verifier`() {
        val v = PkceVerifier.generate(rng)
        assertThat(v.value).hasLength(43)
    }

    @Test
    fun `challenge is the base64-url SHA256 of the verifier`() {
        val v = PkceVerifier.generate(rng)
        val expected =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(v.value.toByteArray(Charsets.US_ASCII)),
            )
        assertThat(v.challenge()).isEqualTo(expected)
    }

    @Test
    fun `two generated verifiers are different`() {
        val a = PkceVerifier.generate(rng)
        val b = PkceVerifier.generate(rng)
        assertThat(a.value).isNotEqualTo(b.value)
    }

    @Test
    fun `of rejects blank`() {
        assertFailure { PkceVerifier.of("") }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `of rejects verifier shorter than 43 characters`() {
        assertFailure { PkceVerifier.of("a".repeat(42)) }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `of rejects verifier longer than 128 characters`() {
        assertFailure { PkceVerifier.of("a".repeat(129)) }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `of rejects characters outside the RFC 7636 unreserved set`() {
        // '+', '/', '=' appear in standard base64 but are not in RFC 7636 §4.1's unreserved set
        assertFailure { PkceVerifier.of("a".repeat(42) + "+") }.isInstanceOf(IllegalArgumentException::class)
        assertFailure { PkceVerifier.of("a".repeat(42) + "=") }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `of accepts a 43-character alphanumeric verifier`() {
        val raw = "a".repeat(43)
        assertThat(PkceVerifier.of(raw).value).isEqualTo(raw)
    }

    @Test
    fun `property - challenge satisfies the RFC 7636 S256 relation for arbitrary valid verifiers`() {
        val unreservedAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        runBlocking {
            checkAll(PropTestConfig(iterations = 200), Arb.string(43..128, unreservedAlphabet)) { raw ->
                val v = PkceVerifier.of(raw)
                val expected =
                    Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.US_ASCII)),
                    )
                assertThat(v.challenge()).isEqualTo(expected)
            }
        }
    }
}
