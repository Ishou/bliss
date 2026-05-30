package com.bliss.identity.application.ports

import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.Role
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

    /** No-op if [id] does not exist. */
    suspend fun updateDisplayName(
        id: UserId,
        name: DisplayName,
    )

    /** No-op if [id] does not exist. */
    suspend fun updateRole(
        id: UserId,
        role: Role,
    )

    suspend fun delete(id: UserId)
}
