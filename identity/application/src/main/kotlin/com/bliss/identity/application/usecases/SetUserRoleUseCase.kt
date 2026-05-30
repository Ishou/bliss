package com.bliss.identity.application.usecases

import com.bliss.identity.application.ports.Clock
import com.bliss.identity.application.ports.UserRepository
import com.bliss.identity.application.ports.UserRoleChangedBroadcaster
import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.UserId

sealed interface SetUserRoleOutcome {
    data class Changed(
        val userId: UserId,
        val role: Role,
    ) : SetUserRoleOutcome

    data class Unchanged(
        val userId: UserId,
        val role: Role,
    ) : SetUserRoleOutcome

    data class UserNotFound(
        val userId: UserId,
    ) : SetUserRoleOutcome
}

class SetUserRoleUseCase(
    private val users: UserRepository,
    private val broadcaster: UserRoleChangedBroadcaster,
    private val clock: Clock,
) {
    suspend fun execute(
        userId: UserId,
        role: Role,
    ): SetUserRoleOutcome {
        val current = users.findById(userId) ?: return SetUserRoleOutcome.UserNotFound(userId)
        if (current.role == role) return SetUserRoleOutcome.Unchanged(userId, role)
        users.updateRole(userId, role)
        broadcaster.broadcast(userId, role, clock.now())
        return SetUserRoleOutcome.Changed(userId, role)
    }
}
