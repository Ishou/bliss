package com.bliss.identity.infrastructure.persistence

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.provider.Subject
import com.bliss.identity.domain.provider.UserProvider
import com.bliss.identity.domain.user.UserId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemoryUserProviderRepositoryTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
    private val userId = UserId(UUID.randomUUID())

    private fun userProvider(
        user: UserId = userId,
        provider: Provider = Provider.GOOGLE,
        subject: String = "google-sub-1",
    ): UserProvider =
        UserProvider(
            userId = user,
            provider = provider,
            subject = Subject.of(subject),
            emailAtLink = null,
            linkedAt = now,
        )

    @Test
    fun `findByProviderAndSubject returns null when empty`() =
        runTest {
            val repo = InMemoryUserProviderRepository()
            assertThat(repo.findByProviderAndSubject(Provider.GOOGLE, Subject.of("x"))).isNull()
        }

    @Test
    fun `link then findByProviderAndSubject round-trips`() =
        runTest {
            val repo = InMemoryUserProviderRepository()
            val up = userProvider()
            repo.link(up)
            assertThat(repo.findByProviderAndSubject(up.provider, up.subject)).isEqualTo(up)
        }

    @Test
    fun `listForUser returns all linkages for a user across providers`() =
        runTest {
            val repo = InMemoryUserProviderRepository()
            val google = userProvider(provider = Provider.GOOGLE, subject = "g-1")
            val apple = userProvider(provider = Provider.APPLE, subject = "a-1")
            repo.link(google)
            repo.link(apple)
            assertThat(repo.listForUser(userId)).containsExactlyInAnyOrder(google, apple)
        }

    @Test
    fun `listForUser returns empty for an unknown user`() =
        runTest {
            val repo = InMemoryUserProviderRepository()
            assertThat(repo.listForUser(UserId(UUID.randomUUID()))).isEmpty()
        }

    @Test
    fun `deleteForUser removes every linkage`() =
        runTest {
            val repo = InMemoryUserProviderRepository()
            repo.link(userProvider(provider = Provider.GOOGLE, subject = "g-1"))
            repo.link(userProvider(provider = Provider.APPLE, subject = "a-1"))
            repo.deleteForUser(userId)
            assertThat(repo.listForUser(userId)).isEmpty()
        }

    @Test
    fun `deleteForUser leaves linkages for other users untouched`() =
        runTest {
            val repo = InMemoryUserProviderRepository()
            val otherUserId = UserId(UUID.randomUUID())
            val mine = userProvider(provider = Provider.GOOGLE, subject = "g-1")
            val theirs = userProvider(user = otherUserId, provider = Provider.APPLE, subject = "a-other")
            repo.link(mine)
            repo.link(theirs)
            repo.deleteForUser(userId)
            assertThat(repo.listForUser(userId)).isEmpty()
            assertThat(repo.listForUser(otherUserId)).containsExactlyInAnyOrder(theirs)
        }

    @Test
    fun `linking the same provider twice for one user throws`() =
        runTest {
            val repo = InMemoryUserProviderRepository()
            val first = userProvider(provider = Provider.GOOGLE, subject = "g-1")
            val second = userProvider(provider = Provider.GOOGLE, subject = "g-2")
            repo.link(first)
            assertFailure { repo.link(second) }.isInstanceOf(IllegalStateException::class)
        }

    @Test
    fun `linking the same provider-subject to a second user throws`() =
        runTest {
            val repo = InMemoryUserProviderRepository()
            val otherUserId = UserId(UUID.randomUUID())
            val first = userProvider(user = userId, provider = Provider.GOOGLE, subject = "g-1")
            val second = userProvider(user = otherUserId, provider = Provider.GOOGLE, subject = "g-1")
            repo.link(first)
            assertFailure { repo.link(second) }.isInstanceOf(IllegalStateException::class)
        }
}
