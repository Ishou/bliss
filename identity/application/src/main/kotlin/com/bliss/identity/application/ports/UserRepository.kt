package com.bliss.identity.application.ports

import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import java.time.Instant

interface UserRepository {
    suspend fun create(user: User)

    suspend fun findById(id: UserId): User?

    suspend fun updateLastSeenAt(id: UserId, at: Instant)

    suspend fun delete(id: UserId)
}
