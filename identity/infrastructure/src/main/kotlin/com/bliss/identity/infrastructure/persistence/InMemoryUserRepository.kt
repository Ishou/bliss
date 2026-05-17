package com.bliss.identity.infrastructure.persistence

import com.bliss.identity.application.ports.UserRepository
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryUserRepository : UserRepository {
    private val byId = ConcurrentHashMap<UserId, User>()

    override suspend fun create(user: User) {
        byId.putIfAbsent(user.id, user)
    }

    override suspend fun findById(id: UserId): User? = byId[id]

    override suspend fun updateLastSeenAt(
        id: UserId,
        at: Instant,
    ) {
        byId.computeIfPresent(id) { _, existing -> existing.copy(lastSeenAt = at) }
    }

    override suspend fun delete(id: UserId) {
        byId.remove(id)
    }
}
