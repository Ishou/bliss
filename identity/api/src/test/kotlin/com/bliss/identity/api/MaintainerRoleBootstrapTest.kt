package com.bliss.identity.api

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import com.bliss.identity.application.testdoubles.FakeUserRepository
import com.bliss.identity.application.testdoubles.FixedClock
import com.bliss.identity.application.testdoubles.RecordingUserRoleChangedBroadcaster
import com.bliss.identity.application.usecases.SetUserRoleOutcome
import com.bliss.identity.application.usecases.SetUserRoleUseCase
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class MaintainerRoleBootstrapTest {
    private val now = Instant.parse("2026-05-30T12:00:00Z")

    @Test
    fun `promotes each configured id and skips blanks`() =
        runTest {
            val users = FakeUserRepository()
            val id1 = UserId(UUID.randomUUID())
            val id2 = UserId(UUID.randomUUID())
            runBlocking {
                users.create(User(id1, DisplayName.of("A"), now, now))
                users.create(User(id2, DisplayName.of("B"), now, now))
            }
            val bc = RecordingUserRoleChangedBroadcaster()
            val useCase = SetUserRoleUseCase(users, bc, FixedClock(now))

            val outcomes = setMaintainerRoles(" ${id1.value} , , ${id2.value} ", useCase)

            assertThat(outcomes).containsExactly(
                SetUserRoleOutcome.Changed(id1, Role.MAINTAINER),
                SetUserRoleOutcome.Changed(id2, Role.MAINTAINER),
            )
            assertThat(bc.events.map { it.userId }).containsExactly(id1, id2)
        }

    @Test
    fun `null or blank config yields no work`() =
        runTest {
            val useCase = SetUserRoleUseCase(FakeUserRepository(), RecordingUserRoleChangedBroadcaster(), FixedClock(now))
            assertThat(setMaintainerRoles(null, useCase)).isEmpty()
            assertThat(setMaintainerRoles("   ", useCase)).isEmpty()
        }
}
