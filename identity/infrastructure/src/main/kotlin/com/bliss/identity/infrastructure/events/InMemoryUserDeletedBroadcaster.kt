package com.bliss.identity.infrastructure.events

import com.bliss.identity.application.ports.UserDeletedBroadcaster
import com.bliss.identity.domain.user.UserId
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class InMemoryUserDeletedBroadcaster : UserDeletedBroadcaster {
    private val events = CopyOnWriteArrayList<Pair<UserId, Instant>>()

    override suspend fun broadcast(
        userId: UserId,
        deletedAt: Instant,
    ) {
        events.add(userId to deletedAt)
    }

    fun captured(): List<Pair<UserId, Instant>> = events.toList()
}
