package com.bliss.identity.domain.user

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RoleTest {
    @Test
    fun `wire values are stable lowercase strings`() {
        assertThat(Role.PLAYER.wire).isEqualTo("player")
        assertThat(Role.MAINTAINER.wire).isEqualTo("maintainer")
    }

    @Test
    fun `fromWire round-trips every role`() {
        Role.entries.forEach { assertThat(Role.fromWire(it.wire)).isEqualTo(it) }
    }

    @Test
    fun `fromWire rejects unknown`() {
        assertFailure { Role.fromWire("admin") }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `new User defaults to PLAYER`() {
        val u =
            User(
                id = UserId(UUID.randomUUID()),
                displayName = DisplayName.of("Alice"),
                createdAt = Instant.parse("2026-05-30T00:00:00Z"),
                lastSeenAt = Instant.parse("2026-05-30T00:00:00Z"),
            )
        assertThat(u.role).isEqualTo(Role.PLAYER)
    }
}
