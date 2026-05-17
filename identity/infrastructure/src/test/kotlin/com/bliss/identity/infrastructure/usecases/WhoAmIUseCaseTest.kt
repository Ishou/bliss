package com.bliss.identity.infrastructure.usecases

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.bliss.identity.application.usecases.WhoAmIError
import com.bliss.identity.application.usecases.WhoAmIQuery
import com.bliss.identity.application.usecases.WhoAmIResult
import com.bliss.identity.application.usecases.WhoAmIUseCase
import com.bliss.identity.domain.session.Session
import com.bliss.identity.domain.session.SessionId
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import com.bliss.identity.infrastructure.persistence.InMemorySessionRepository
import com.bliss.identity.infrastructure.persistence.InMemoryUserRepository
import com.bliss.identity.infrastructure.testdoubles.FixedClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class WhoAmIUseCaseTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
    private val sessionMaxAge: Duration = Duration.ofDays(7)
    private val userId = UserId(UUID.randomUUID())
    private val sessionId = SessionId(UUID.randomUUID())

    private fun newCase(
        users: InMemoryUserRepository = InMemoryUserRepository(),
        sessions: InMemorySessionRepository = InMemorySessionRepository(),
        clock: FixedClock = FixedClock(now),
    ): Triple<WhoAmIUseCase, InMemoryUserRepository, InMemorySessionRepository> =
        Triple(
            WhoAmIUseCase(users, sessions, clock, sessionMaxAge),
            users,
            sessions,
        )

    private fun seedUserAndSession(
        users: InMemoryUserRepository,
        sessions: InMemorySessionRepository,
        createdAt: Instant = now.minusSeconds(60),
        revokedAt: Instant? = null,
    ) {
        runTest {
            users.create(User(userId, DisplayName.of("Alice"), createdAt, createdAt))
            sessions.create(Session(sessionId, userId, createdAt, createdAt, revokedAt))
        }
    }

    @Test
    fun `unknown session throws SessionNotFound`() = runTest {
        val (sut, _, _) = newCase()
        assertFailure { sut.execute(WhoAmIQuery(SessionId(UUID.randomUUID()))) }
            .isInstanceOf(WhoAmIError.SessionNotFound::class)
    }

    @Test
    fun `revoked session throws SessionRevoked`() = runTest {
        val (sut, users, sessions) = newCase()
        seedUserAndSession(users, sessions, revokedAt = now.minusSeconds(30))
        assertFailure { sut.execute(WhoAmIQuery(sessionId)) }
            .isInstanceOf(WhoAmIError.SessionRevoked::class)
    }

    @Test
    fun `session older than sessionMaxAge throws SessionExpired`() = runTest {
        val (sut, users, sessions) = newCase()
        val tooOld = now.minus(sessionMaxAge).minusSeconds(1)
        seedUserAndSession(users, sessions, createdAt = tooOld)
        assertFailure { sut.execute(WhoAmIQuery(sessionId)) }
            .isInstanceOf(WhoAmIError.SessionExpired::class)
    }

    @Test
    fun `session linked to deleted user throws OrphanedSession`() = runTest {
        val (sut, _, sessions) = newCase()
        sessions.create(Session(sessionId, userId, now.minusSeconds(60), now.minusSeconds(60), null))
        assertFailure { sut.execute(WhoAmIQuery(sessionId)) }
            .isInstanceOf(WhoAmIError.OrphanedSession::class)
    }

    @Test
    fun `happy path returns userId and displayName`() = runTest {
        val (sut, users, sessions) = newCase()
        seedUserAndSession(users, sessions)
        val result = sut.execute(WhoAmIQuery(sessionId))
        assertThat(result).isEqualTo(WhoAmIResult(userId, DisplayName.of("Alice")))
    }
}
