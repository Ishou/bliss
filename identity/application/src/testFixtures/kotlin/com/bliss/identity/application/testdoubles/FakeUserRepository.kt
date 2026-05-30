package com.bliss.identity.application.testdoubles

import com.bliss.identity.application.ports.UserRepository
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import java.time.Instant

/** Minimal in-memory UserRepository for application-layer tests (no infrastructure dependency). */
class FakeUserRepository : UserRepository {
    private val byId = LinkedHashMap<UserId, User>()

    override suspend fun create(user: User) {
        byId.putIfAbsent(user.id, user)
    }

    override suspend fun findById(id: UserId): User? = byId[id]

    override suspend fun updateLastSeenAt(
        id: UserId,
        at: Instant,
    ) {
        byId[id]?.let { byId[id] = it.copy(lastSeenAt = at) }
    }

    override suspend fun updateDisplayName(
        id: UserId,
        name: DisplayName,
    ) {
        byId[id]?.let { byId[id] = it.copy(displayName = name) }
    }

    override suspend fun updateRole(
        id: UserId,
        role: Role,
    ) {
        byId[id]?.let { byId[id] = it.copy(role = role) }
    }

    override suspend fun delete(id: UserId) {
        byId.remove(id)
    }
}
