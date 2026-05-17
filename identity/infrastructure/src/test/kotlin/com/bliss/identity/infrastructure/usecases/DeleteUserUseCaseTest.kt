package com.bliss.identity.infrastructure.usecases

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import com.bliss.identity.application.ports.UserDeletedBroadcaster
import com.bliss.identity.application.usecases.DeleteUserCommand
import com.bliss.identity.application.usecases.DeleteUserError
import com.bliss.identity.application.usecases.DeleteUserUseCase
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import com.bliss.identity.infrastructure.events.InMemoryUserDeletedBroadcaster
import com.bliss.identity.infrastructure.persistence.InMemoryUserRepository
import com.bliss.identity.infrastructure.testdoubles.FixedClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DeleteUserUseCaseTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
    private val userId = UserId(UUID.randomUUID())

    @Test
    fun `unknown user throws UserNotFound`() =
        runTest {
            val sut =
                DeleteUserUseCase(
                    users = InMemoryUserRepository(),
                    broadcaster = InMemoryUserDeletedBroadcaster(),
                    clock = FixedClock(now),
                )
            assertFailure { sut.execute(DeleteUserCommand(userId)) }
                .isInstanceOf(DeleteUserError.UserNotFound::class)
        }

    @Test
    fun `happy path broadcasts then deletes locally`() =
        runTest {
            val users = InMemoryUserRepository()
            users.create(User(userId, DisplayName.of("Alice"), now, now))
            val broadcaster = InMemoryUserDeletedBroadcaster()
            val sut = DeleteUserUseCase(users = users, broadcaster = broadcaster, clock = FixedClock(now))
            sut.execute(DeleteUserCommand(userId))
            assertThat(broadcaster.captured()).hasSize(1)
            assertThat(broadcaster.captured()).contains(userId to now)
            assertThat(users.findById(userId)).isNull()
        }

    @Test
    fun `broadcaster failure prevents local delete`() =
        runTest {
            val users = InMemoryUserRepository()
            users.create(User(userId, DisplayName.of("Alice"), now, now))
            val downstreamError = RuntimeException("downstream is down")
            val flakyBroadcaster =
                object : UserDeletedBroadcaster {
                    override suspend fun broadcast(
                        userId: UserId,
                        deletedAt: Instant,
                    ): Unit = throw downstreamError
                }
            val sut = DeleteUserUseCase(users = users, broadcaster = flakyBroadcaster, clock = FixedClock(now))
            val failure = assertFailure { sut.execute(DeleteUserCommand(userId)) }
            failure.isInstanceOf(DeleteUserError.BroadcastFailed::class)
            failure.given { thrown ->
                assertThat((thrown as DeleteUserError.BroadcastFailed).cause).isSameInstanceAs(downstreamError)
            }
            // User row still present - local delete did not run.
            assertThat(users.findById(userId)).isNotNull()
        }

    @Test
    fun `CancellationException from broadcaster propagates unwrapped`() =
        runTest {
            val users = InMemoryUserRepository()
            users.create(User(userId, DisplayName.of("Alice"), now, now))
            val cancellingBroadcaster =
                object : UserDeletedBroadcaster {
                    override suspend fun broadcast(
                        userId: UserId,
                        deletedAt: Instant,
                    ): Unit = throw kotlin.coroutines.cancellation.CancellationException("cancelled")
                }
            val sut = DeleteUserUseCase(users = users, broadcaster = cancellingBroadcaster, clock = FixedClock(now))
            assertFailure { sut.execute(DeleteUserCommand(userId)) }
                .isInstanceOf(kotlin.coroutines.cancellation.CancellationException::class)
            // User still present — cancellation arrived before the local delete.
            assertThat(users.findById(userId)).isNotNull()
        }
}
