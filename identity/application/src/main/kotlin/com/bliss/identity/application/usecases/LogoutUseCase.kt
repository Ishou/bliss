package com.bliss.identity.application.usecases

import com.bliss.identity.application.ports.Clock
import com.bliss.identity.application.ports.SessionRepository
import com.bliss.identity.domain.session.SessionId

data class LogoutCommand(val sessionId: SessionId)

class LogoutUseCase(
    private val sessions: SessionRepository,
    private val clock: Clock,
) {
    suspend fun execute(command: LogoutCommand) {
        sessions.revoke(command.sessionId, clock.now())
    }
}
