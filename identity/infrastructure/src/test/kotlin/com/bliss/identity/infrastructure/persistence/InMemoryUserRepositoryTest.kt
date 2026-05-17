package com.bliss.identity.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemoryUserRepositoryTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")

    private fun user(id: UUID = UUID.randomUUID()): User =
        User(
            id = UserId(id),
            displayName = DisplayName.of("Alice"),
            createdAt = now,
            lastSeenAt = now,
        )

    @Test
    fun `findById returns null when empty`() =
        runTest {
            val repo = InMemoryUserRepository()
            assertThat(repo.findById(UserId(UUID.randomUUID()))).isNull()
        }

    @Test
    fun `create then findById round-trips`() =
        runTest {
            val repo = InMemoryUserRepository()
            val u = user()
            repo.create(u)
            assertThat(repo.findById(u.id)).isEqualTo(u)
        }

    @Test
    fun `updateLastSeenAt updates the timestamp`() =
        runTest {
            val repo = InMemoryUserRepository()
            val u = user()
            repo.create(u)
            val later = now.plusSeconds(60)
            repo.updateLastSeenAt(u.id, later)
            assertThat(repo.findById(u.id)?.lastSeenAt).isEqualTo(later)
        }

    @Test
    fun `updateLastSeenAt is a no-op for an unknown user`() =
        runTest {
            val repo = InMemoryUserRepository()
            repo.updateLastSeenAt(UserId(UUID.randomUUID()), now)
            // No throw, no row created.
        }

    @Test
    fun `delete removes the user`() =
        runTest {
            val repo = InMemoryUserRepository()
            val u = user()
            repo.create(u)
            repo.delete(u.id)
            assertThat(repo.findById(u.id)).isNull()
        }

    @Test
    fun `delete is a no-op for an unknown user`() =
        runTest {
            val repo = InMemoryUserRepository()
            repo.delete(UserId(UUID.randomUUID()))
            // No throw, no row created.
        }

    @Test
    fun `create is idempotent for an existing user`() =
        runTest {
            val repo = InMemoryUserRepository()
            val u = user()
            repo.create(u)
            repo.create(u.copy(displayName = DisplayName.of("Updated")))
            assertThat(repo.findById(u.id)).isEqualTo(u)
        }

    @Test
    fun `updateDisplayName updates the stored name`() =
        runTest {
            val repo = InMemoryUserRepository()
            val u = user()
            repo.create(u)
            repo.updateDisplayName(u.id, DisplayName.of("Bob"))
            assertThat(repo.findById(u.id)?.displayName).isEqualTo(DisplayName.of("Bob"))
        }

    @Test
    fun `updateDisplayName is a no-op for an unknown user`() =
        runTest {
            val repo = InMemoryUserRepository()
            val unknownId = UserId(UUID.randomUUID())
            repo.updateDisplayName(unknownId, DisplayName.of("Ghost"))
            assertThat(repo.findById(unknownId)).isNull()
        }
}
