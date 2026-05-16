package com.bliss.identity.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.bliss.identity.domain.session.Session
import com.bliss.identity.domain.session.SessionId
import com.bliss.identity.domain.user.UserId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemorySessionRepositoryTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
    private val userId = UserId(UUID.randomUUID())

    private fun session(id: UUID = UUID.randomUUID()): Session =
        Session(
            id = SessionId(id),
            userId = userId,
            createdAt = now,
            lastSeenAt = now,
            revokedAt = null,
        )

    @Test
    fun `findById returns null when empty`() = runTest {
        val repo = InMemorySessionRepository()
        assertThat(repo.findById(SessionId(UUID.randomUUID()))).isNull()
    }

    @Test
    fun `create then findById round-trips`() = runTest {
        val repo = InMemorySessionRepository()
        val s = session()
        repo.create(s)
        assertThat(repo.findById(s.id)).isEqualTo(s)
    }

    @Test
    fun `revoke sets revokedAt and makes isActive false`() = runTest {
        val repo = InMemorySessionRepository()
        val s = session()
        repo.create(s)
        val revokedAt = now.plusSeconds(120)
        repo.revoke(s.id, revokedAt)
        val after = repo.findById(s.id)!!
        assertThat(after.revokedAt).isEqualTo(revokedAt)
        assertThat(after.isActive).isFalse()
    }

    @Test
    fun `revoke is a no-op for an unknown session`() = runTest {
        val repo = InMemorySessionRepository()
        repo.revoke(SessionId(UUID.randomUUID()), now)
        // No throw, no row created.
    }

    @Test
    fun `deleteForUser removes every session for the user`() = runTest {
        val repo = InMemorySessionRepository()
        val a = session()
        val b = session()
        repo.create(a)
        repo.create(b)
        repo.deleteForUser(userId)
        assertThat(repo.findById(a.id)).isNull()
        assertThat(repo.findById(b.id)).isNull()
    }
}
