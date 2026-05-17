package com.bliss.identity.application.usecases

import com.bliss.identity.application.ports.Clock
import com.bliss.identity.application.ports.SessionRepository
import com.bliss.identity.application.ports.UserRepository
import com.bliss.identity.domain.session.SessionId
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.UserId
import java.time.Duration

data class WhoAmIQuery(
    val sessionId: SessionId,
)

data class WhoAmIResult(
    val userId: UserId,
    val displayName: DisplayName,
)

class WhoAmIUseCase(
    private val users: UserRepository,
    private val sessions: SessionRepository,
    private val clock: Clock,
    private val sessionMaxAge: Duration,
) {
    suspend fun execute(query: WhoAmIQuery): WhoAmIResult {
        val session =
            sessions.findById(query.sessionId)
                ?: throw WhoAmIError.SessionNotFound()
        if (!session.isActive) throw WhoAmIError.SessionRevoked()
        val age = Duration.between(session.createdAt, clock.now())
        if (age > sessionMaxAge) throw WhoAmIError.SessionExpired()
        val user =
            users.findById(session.userId)
                ?: throw WhoAmIError.OrphanedSession()
        return WhoAmIResult(user.id, user.displayName)
    }
}
