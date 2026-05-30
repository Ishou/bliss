package com.bliss.identity.application.usecases

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.bliss.identity.application.testdoubles.FakeUserRepository
import com.bliss.identity.application.testdoubles.FixedClock
import com.bliss.identity.application.testdoubles.RecordingUserRoleChangedBroadcaster
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SetUserRoleUseCaseTest {
    private val now = Instant.parse("2026-05-30T12:00:00Z")
    private val clock = FixedClock(now)

    private fun fixture(role: Role = Role.PLAYER): Pair<FakeUserRepository, UserId> {
        val users = FakeUserRepository()
        val id = UserId(UUID.randomUUID())
        runBlocking {
            users.create(User(id, DisplayName.of("Alice"), now, now, role))
        }
        return users to id
    }

    @Test
    fun `promotes a player and broadcasts once`() =
        runTest {
            val (users, id) = fixture()
            val bc = RecordingUserRoleChangedBroadcaster()
            val outcome = SetUserRoleUseCase(users, bc, clock).execute(id, Role.MAINTAINER)

            assertThat(outcome).isInstanceOf(SetUserRoleOutcome.Changed::class)
            assertThat(users.findById(id)?.role).isEqualTo(Role.MAINTAINER)
            assertThat(bc.events).hasSize(1)
            assertThat(bc.events.first().role).isEqualTo(Role.MAINTAINER)
            assertThat(bc.events.first().changedAt).isEqualTo(now)
        }

    @Test
    fun `unchanged role is a no-op and emits no event`() =
        runTest {
            val (users, id) = fixture(role = Role.MAINTAINER)
            val bc = RecordingUserRoleChangedBroadcaster()
            val outcome = SetUserRoleUseCase(users, bc, clock).execute(id, Role.MAINTAINER)

            assertThat(outcome).isInstanceOf(SetUserRoleOutcome.Unchanged::class)
            assertThat(bc.events).isEmpty()
        }

    @Test
    fun `unknown user does not broadcast`() =
        runTest {
            val users = FakeUserRepository()
            val bc = RecordingUserRoleChangedBroadcaster()
            val outcome = SetUserRoleUseCase(users, bc, clock).execute(UserId(UUID.randomUUID()), Role.MAINTAINER)

            assertThat(outcome).isInstanceOf(SetUserRoleOutcome.UserNotFound::class)
            assertThat(bc.events).isEmpty()
        }
}
