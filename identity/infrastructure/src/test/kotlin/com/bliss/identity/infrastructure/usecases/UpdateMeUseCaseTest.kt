package com.bliss.identity.infrastructure.usecases

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.bliss.identity.application.usecases.UpdateMeCommand
import com.bliss.identity.application.usecases.UpdateMeError
import com.bliss.identity.application.usecases.UpdateMeUseCase
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import com.bliss.identity.infrastructure.persistence.InMemoryUserRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class UpdateMeUseCaseTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
    private val userId = UserId(UUID.randomUUID())

    private fun newCase(users: InMemoryUserRepository = InMemoryUserRepository()): Pair<UpdateMeUseCase, InMemoryUserRepository> =
        UpdateMeUseCase(users) to users

    private suspend fun seedUser(users: InMemoryUserRepository): User {
        val u = User(userId, DisplayName.of("Alice"), now, now)
        users.create(u)
        return u
    }

    @Test
    fun `unknown user throws UserNotFound`() =
        runTest {
            val (sut, _) = newCase()
            assertFailure { sut.execute(UpdateMeCommand(userId, "Bob")) }
                .isInstanceOf(UpdateMeError.UserNotFound::class)
        }

    @Test
    fun `valid displayName update persists the new name`() =
        runTest {
            val (sut, users) = newCase()
            seedUser(users)
            sut.execute(UpdateMeCommand(userId, "Bob"))
            assertThat(users.findById(userId)?.displayName).isEqualTo(DisplayName.of("Bob"))
        }

    @Test
    fun `null displayName is a no-op - original name preserved`() =
        runTest {
            val (sut, users) = newCase()
            seedUser(users)
            sut.execute(UpdateMeCommand(userId, null))
            assertThat(users.findById(userId)?.displayName).isEqualTo(DisplayName.of("Alice"))
        }

    @Test
    fun `blank displayName throws InvalidDisplayName`() =
        runTest {
            val (sut, users) = newCase()
            seedUser(users)
            assertFailure { sut.execute(UpdateMeCommand(userId, "   ")) }
                .isInstanceOf(UpdateMeError.InvalidDisplayName::class)
        }

    @Test
    fun `displayName exceeding 30 chars throws InvalidDisplayName`() =
        runTest {
            val (sut, users) = newCase()
            seedUser(users)
            assertFailure { sut.execute(UpdateMeCommand(userId, "a".repeat(31))) }
                .isInstanceOf(UpdateMeError.InvalidDisplayName::class)
        }

    @Test
    fun `emailOptIn field is accepted without error - no-op in v1`() =
        runTest {
            val (sut, users) = newCase()
            seedUser(users)
            sut.execute(UpdateMeCommand(userId, null, emailOptIn = true))
            assertThat(users.findById(userId)?.displayName).isEqualTo(DisplayName.of("Alice"))
        }
}
