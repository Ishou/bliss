package com.bliss.identity.infrastructure.id

import com.bliss.identity.application.ports.IdGenerator
import com.bliss.identity.domain.auth.AuthAttemptId
import com.bliss.identity.domain.session.SessionId
import com.bliss.identity.domain.user.UserId
import java.util.UUID

/**
 * Test double — returns the IDs in the configured sequence. Throws if the
 * caller asks for more IDs of a given kind than were configured. Production
 * binding (UUIDv7 generator) lands in Phase 3.
 */
class FixedIdGenerator(
    userIds: List<UUID> = emptyList(),
    sessionIds: List<UUID> = emptyList(),
    authAttemptIds: List<UUID> = emptyList(),
) : IdGenerator {
    private val users = ArrayDeque(userIds)
    private val sessions = ArrayDeque(sessionIds)
    private val authAttempts = ArrayDeque(authAttemptIds)

    override fun newUserId(): UserId = UserId(users.removeFirstOrNull() ?: error("FixedIdGenerator exhausted for UserId."))

    override fun newSessionId(): SessionId = SessionId(sessions.removeFirstOrNull() ?: error("FixedIdGenerator exhausted for SessionId."))

    override fun newAuthAttemptId(): AuthAttemptId =
        AuthAttemptId(authAttempts.removeFirstOrNull() ?: error("FixedIdGenerator exhausted for AuthAttemptId."))
}
