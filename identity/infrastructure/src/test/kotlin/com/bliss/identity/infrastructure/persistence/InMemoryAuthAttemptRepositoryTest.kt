package com.bliss.identity.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.bliss.identity.domain.auth.AuthAttempt
import com.bliss.identity.domain.auth.AuthAttemptId
import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.auth.State
import com.bliss.identity.domain.provider.Provider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

class InMemoryAuthAttemptRepositoryTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
    private val random = SecureRandom()

    private fun attempt(state: State = State.generate(random)): AuthAttempt =
        AuthAttempt(
            id = AuthAttemptId(UUID.randomUUID()),
            state = state,
            pkceVerifier = PkceVerifier.generate(random),
            provider = Provider.GOOGLE,
            returnTo = "https://example/",
            linkToUserId = null,
            expiresAt = now.plusSeconds(300),
        )

    @Test
    fun `findByState returns null when empty`() = runTest {
        val repo = InMemoryAuthAttemptRepository()
        assertThat(repo.findByState(State.generate(random))).isNull()
    }

    @Test
    fun `create then findByState round-trips`() = runTest {
        val repo = InMemoryAuthAttemptRepository()
        val a = attempt()
        repo.create(a)
        assertThat(repo.findByState(a.state)).isEqualTo(a)
    }

    @Test
    fun `deleteByState removes the row`() = runTest {
        val repo = InMemoryAuthAttemptRepository()
        val a = attempt()
        repo.create(a)
        repo.deleteByState(a.state)
        assertThat(repo.findByState(a.state)).isNull()
    }

    @Test
    fun `deleteByState is a no-op when the state is unknown`() = runTest {
        val repo = InMemoryAuthAttemptRepository()
        repo.deleteByState(State.generate(random))
        // No throw.
    }
}
