package com.bliss.identity.domain.session

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.bliss.identity.domain.user.UserId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SessionTest {
    private val now: Instant = Instant.parse("2026-05-16T12:00:00Z")

    private fun session(revokedAt: Instant? = null): Session =
        Session(
            id = SessionId(UUID.randomUUID()),
            userId = UserId(UUID.randomUUID()),
            createdAt = now,
            lastSeenAt = now,
            revokedAt = revokedAt,
        )

    @Test
    fun `is active when revokedAt is null`() {
        assertThat(session(revokedAt = null).isActive).isTrue()
    }

    @Test
    fun `is not active when revokedAt is set`() {
        assertThat(session(revokedAt = now).isActive).isFalse()
    }
}
