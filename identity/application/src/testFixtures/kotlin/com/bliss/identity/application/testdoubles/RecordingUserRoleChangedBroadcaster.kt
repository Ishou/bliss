package com.bliss.identity.application.testdoubles

import com.bliss.identity.application.ports.UserRoleChangedBroadcaster
import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.UserId
import java.time.Instant

class RecordingUserRoleChangedBroadcaster : UserRoleChangedBroadcaster {
    data class Event(
        val userId: UserId,
        val role: Role,
        val changedAt: Instant,
    )

    val events = mutableListOf<Event>()

    override suspend fun broadcast(
        userId: UserId,
        role: Role,
        changedAt: Instant,
    ) {
        events.add(Event(userId, role, changedAt))
    }
}
