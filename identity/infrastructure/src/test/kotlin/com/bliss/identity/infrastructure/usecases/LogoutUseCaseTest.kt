package com.bliss.identity.infrastructure.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import com.bliss.identity.application.usecases.LogoutCommand
import com.bliss.identity.application.usecases.LogoutUseCase
import com.bliss.identity.domain.session.Session
import com.bliss.identity.domain.session.SessionId
import com.bliss.identity.domain.user.UserId
import com.bliss.identity.infrastructure.persistence.InMemorySessionRepository
import com.bliss.identity.infrastructure.testdoubles.FixedClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class LogoutUseCaseTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
    private val sessionId = SessionId(UUID.randomUUID())

    private fun newCase(
        sessions: InMemorySessionRepository = InMemorySessionRepository(),
        clock: FixedClock = FixedClock(now),
    ): Pair<LogoutUseCase, InMemorySessionRepository> = LogoutUseCase(sessions, clock) to sessions

    @Test
    fun `revokes the active session`() =
        runTest {
            val (sut, sessions) = newCase()
            sessions.create(
                Session(sessionId, UserId(UUID.randomUUID()), now.minusSeconds(60), now.minusSeconds(60), null),
            )
            sut.execute(LogoutCommand(sessionId))
            val after = sessions.findById(sessionId)
            assertThat(after).isNotNull()
            assertThat(after!!.isActive).isFalse()
            assertThat(after.revokedAt).isEqualTo(now)
        }

    @Test
    fun `unknown session is a silent no-op`() =
        runTest {
            val (sut, sessions) = newCase()
            sut.execute(LogoutCommand(SessionId(UUID.randomUUID())))
            // No throw, no side-effect - the port contract.
            assertThat(sessions.findById(sessionId)).isEqualTo(null)
        }

    @Test
    fun `second revoke preserves the original revokedAt`() =
        runTest {
            val (sut, sessions) = newCase(clock = FixedClock(now))
            sessions.create(
                Session(sessionId, UserId(UUID.randomUUID()), now.minusSeconds(60), now.minusSeconds(60), null),
            )
            sut.execute(LogoutCommand(sessionId))
            val firstRevoke = sessions.findById(sessionId)!!.revokedAt
            // Advance clock and revoke again.
            val laterClock = FixedClock(now.plusSeconds(120))
            val (later, _) = newCase(sessions = sessions, clock = laterClock)
            later.execute(LogoutCommand(sessionId))
            assertThat(sessions.findById(sessionId)!!.revokedAt).isEqualTo(firstRevoke)
        }
}
