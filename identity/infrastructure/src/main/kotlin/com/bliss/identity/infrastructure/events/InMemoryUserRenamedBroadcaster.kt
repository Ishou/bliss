package com.bliss.identity.infrastructure.events

import com.bliss.identity.application.ports.UserRenamedBroadcaster
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.UserId
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class InMemoryUserRenamedBroadcaster : UserRenamedBroadcaster {
    private val events = CopyOnWriteArrayList<Triple<UserId, DisplayName, Instant>>()

    override suspend fun broadcast(
        userId: UserId,
        newDisplayName: DisplayName,
        renamedAt: Instant,
    ) {
        events.add(Triple(userId, newDisplayName, renamedAt))
    }

    fun captured(): List<Triple<UserId, DisplayName, Instant>> = events.toList()
}
