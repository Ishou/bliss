package com.bliss.identity.application.ports

import com.bliss.identity.domain.session.Session
import com.bliss.identity.domain.session.SessionId
import com.bliss.identity.domain.user.UserId
import java.time.Instant

interface SessionRepository {
    suspend fun create(session: Session)

    suspend fun findById(id: SessionId): Session?

    /**
     * Revoke a session. Idempotent: a second call on an already-revoked session is a no-op
     * (the original `revokedAt` is preserved, not overwritten). No-op for an unknown session id.
     */
    suspend fun revoke(
        id: SessionId,
        at: Instant,
    )

    suspend fun deleteForUser(userId: UserId)
}
