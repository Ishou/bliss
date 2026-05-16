package com.bliss.identity.infrastructure.persistence

import com.bliss.identity.application.ports.SessionRepository
import com.bliss.identity.domain.session.Session
import com.bliss.identity.domain.session.SessionId
import com.bliss.identity.domain.user.UserId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemorySessionRepository : SessionRepository {
    private val byId = ConcurrentHashMap<SessionId, Session>()

    override suspend fun create(session: Session) {
        byId[session.id] = session
    }

    override suspend fun findById(id: SessionId): Session? = byId[id]

    override suspend fun revoke(
        id: SessionId,
        at: Instant,
    ) {
        byId.computeIfPresent(id) { _, existing -> existing.copy(revokedAt = at) }
    }

    override suspend fun deleteForUser(userId: UserId) {
        byId.values
            .filter { it.userId == userId }
            .forEach { byId.remove(it.id) }
    }
}
