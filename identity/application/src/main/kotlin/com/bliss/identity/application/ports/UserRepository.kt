package com.bliss.identity.application.ports

import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import java.time.Instant

interface UserRepository {
    /** Idempotent — no-op if a user with the same [UserId] already exists. */
    suspend fun create(user: User)

    suspend fun findById(id: UserId): User?

    /**
     * Update the user's last-seen timestamp. No-op if the user does not exist —
     * a returning session for a deleted user gracefully degrades rather than throwing.
     */
    suspend fun updateLastSeenAt(
        id: UserId,
        at: Instant,
    )

    suspend fun delete(id: UserId)
}
