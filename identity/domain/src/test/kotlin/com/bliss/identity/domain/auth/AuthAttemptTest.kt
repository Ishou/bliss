package com.bliss.identity.domain.auth

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.user.UserId
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

class AuthAttemptTest {
    private val expiresAt: Instant = Instant.parse("2026-05-16T12:00:00Z")

    private val rng = SecureRandom()

    private fun attempt(linkToUserId: UserId? = null): AuthAttempt =
        AuthAttempt(
            id = AuthAttemptId(UUID.randomUUID()),
            state = State.generate(rng),
            pkceVerifier = PkceVerifier.generate(rng),
            provider = Provider.GOOGLE,
            returnTo = "https://example/",
            linkToUserId = linkToUserId,
            expiresAt = expiresAt,
        )

    @Test
    fun `is not expired just before expiresAt`() {
        assertThat(attempt().isExpired(expiresAt.minusNanos(1))).isFalse()
    }

    @Test
    fun `is expired at exactly expiresAt`() {
        assertThat(attempt().isExpired(expiresAt)).isTrue()
    }

    @Test
    fun `is expired after expiresAt`() {
        assertThat(attempt().isExpired(expiresAt.plusSeconds(1))).isTrue()
    }

    @Test
    fun `isLinkingMode is false when linkToUserId is null`() {
        assertThat(attempt(linkToUserId = null).isLinkingMode).isFalse()
    }

    @Test
    fun `isLinkingMode is true when linkToUserId is set`() {
        val userId = UserId(UUID.randomUUID())
        assertThat(attempt(linkToUserId = userId).isLinkingMode).isTrue()
    }
}
