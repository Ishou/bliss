package com.bliss.identity.infrastructure.usecases

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.bliss.identity.application.usecases.UpdateMeCommand
import com.bliss.identity.application.usecases.UpdateMeError
import com.bliss.identity.application.usecases.UpdateMeUseCase
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import com.bliss.identity.infrastructure.events.InMemoryUserRenamedBroadcaster
import com.bliss.identity.infrastructure.persistence.InMemoryUserRepository
import com.bliss.identity.infrastructure.testdoubles.FixedClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class UpdateMeUseCaseTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
    private val userId = UserId(UUID.randomUUID())

    private data class Sut(
        val useCase: UpdateMeUseCase,
        val users: InMemoryUserRepository,
        val broadcaster: InMemoryUserRenamedBroadcaster,
    )

    private fun newCase(): Sut {
        val users = InMemoryUserRepository()
        val broadcaster = InMemoryUserRenamedBroadcaster()
        return Sut(UpdateMeUseCase(users, broadcaster, FixedClock(now)), users, broadcaster)
    }

    private suspend fun seedUser(users: InMemoryUserRepository): User {
        val u = User(userId, DisplayName.of("Alice"), now, now)
        users.create(u)
        return u
    }

    @Test
    fun `unknown user throws UserNotFound`() =
        runTest {
            val sut = newCase()
            assertFailure { sut.useCase.execute(UpdateMeCommand(userId, "Bob")) }
                .isInstanceOf(UpdateMeError.UserNotFound::class)
        }

    @Test
    fun `valid displayName update persists the new name`() =
        runTest {
            val sut = newCase()
            seedUser(sut.users)
            sut.useCase.execute(UpdateMeCommand(userId, "Bob"))
            assertThat(sut.users.findById(userId)?.displayName).isEqualTo(DisplayName.of("Bob"))
        }

    @Test
    fun `successful rename broadcasts user-renamed event`() =
        runTest {
            val sut = newCase()
            seedUser(sut.users)
            sut.useCase.execute(UpdateMeCommand(userId, "Renard 888"))
            val captured = sut.broadcaster.captured()
            assertThat(captured.size).isEqualTo(1)
            assertThat(captured[0].first).isEqualTo(userId)
            assertThat(captured[0].second).isEqualTo(DisplayName.of("Renard 888"))
            assertThat(captured[0].third).isEqualTo(now)
        }

    @Test
    fun `noop rename does not broadcast`() =
        runTest {
            val sut = newCase()
            seedUser(sut.users)
            sut.useCase.execute(UpdateMeCommand(userId, "Alice"))
            assertThat(sut.broadcaster.captured()).isEmpty()
        }

    @Test
    fun `null displayName is a no-op - original name preserved and no broadcast`() =
        runTest {
            val sut = newCase()
            seedUser(sut.users)
            sut.useCase.execute(UpdateMeCommand(userId, null))
            assertThat(sut.users.findById(userId)?.displayName).isEqualTo(DisplayName.of("Alice"))
            assertThat(sut.broadcaster.captured()).isEmpty()
        }

    @Test
    fun `blank displayName throws InvalidDisplayName`() =
        runTest {
            val sut = newCase()
            seedUser(sut.users)
            assertFailure { sut.useCase.execute(UpdateMeCommand(userId, "   ")) }
                .isInstanceOf(UpdateMeError.InvalidDisplayName::class)
        }

    @Test
    fun `displayName exceeding 30 chars throws InvalidDisplayName`() =
        runTest {
            val sut = newCase()
            seedUser(sut.users)
            assertFailure { sut.useCase.execute(UpdateMeCommand(userId, "a".repeat(31))) }
                .isInstanceOf(UpdateMeError.InvalidDisplayName::class)
        }

    @Test
    fun `emailOptIn field is accepted without error - no-op in v1`() =
        runTest {
            val sut = newCase()
            seedUser(sut.users)
            sut.useCase.execute(UpdateMeCommand(userId, null, emailOptIn = true))
            assertThat(sut.users.findById(userId)?.displayName).isEqualTo(DisplayName.of("Alice"))
            assertThat(sut.broadcaster.captured()).isEmpty()
        }
}
