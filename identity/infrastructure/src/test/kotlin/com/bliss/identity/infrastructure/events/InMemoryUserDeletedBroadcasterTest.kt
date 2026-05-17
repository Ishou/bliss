package com.bliss.identity.infrastructure.events

import assertk.assertThat
import assertk.assertions.containsExactly
import com.bliss.identity.domain.user.UserId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemoryUserDeletedBroadcasterTest {
    @Test
    fun `captures every broadcast in order`() =
        runTest {
            val broadcaster = InMemoryUserDeletedBroadcaster()
            val now = Instant.parse("2026-05-17T12:00:00Z")
            val u1 = UserId(UUID.randomUUID())
            val u2 = UserId(UUID.randomUUID())
            broadcaster.broadcast(u1, now)
            broadcaster.broadcast(u2, now.plusSeconds(1))
            assertThat(broadcaster.captured()).containsExactly(
                u1 to now,
                u2 to now.plusSeconds(1),
            )
        }
}
