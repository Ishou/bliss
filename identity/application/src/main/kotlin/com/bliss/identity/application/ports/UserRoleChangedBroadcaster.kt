package com.bliss.identity.application.ports

import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.UserId
import java.time.Instant

/** Fire-and-forget notification of a role change (ADR-0049). */
fun interface UserRoleChangedBroadcaster {
    suspend fun broadcast(
        userId: UserId,
        role: Role,
        changedAt: Instant,
    )
}
