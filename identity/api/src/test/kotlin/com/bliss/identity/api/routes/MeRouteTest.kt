package com.bliss.identity.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import com.bliss.identity.api.Wiring
import com.bliss.identity.api.auth.SessionCookies
import com.bliss.identity.api.config.AppleClientConfig
import com.bliss.identity.api.config.GoogleClientConfig
import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.api.module
import com.bliss.identity.application.usecases.GetMeUseCase
import com.bliss.identity.application.usecases.WhoAmIUseCase
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.provider.Subject
import com.bliss.identity.domain.provider.UserProvider
import com.bliss.identity.domain.session.Session
import com.bliss.identity.domain.session.SessionId
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import com.bliss.identity.infrastructure.persistence.InMemorySessionRepository
import com.bliss.identity.infrastructure.persistence.InMemoryUserProviderRepository
import com.bliss.identity.infrastructure.persistence.InMemoryUserRepository
import com.bliss.identity.infrastructure.testdoubles.FixedClock
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class MeRouteTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
    private val linkedAt: Instant = Instant.parse("2026-05-16T10:30:00Z")
    private val userId = UserId(UUID.randomUUID())
    private val sessionId = SessionId(UUID.randomUUID())

    private val testConfig =
        IdentityApiConfig(
            port = 0,
            publicHost = "localhost",
            google = GoogleClientConfig("g-client", "g-secret"),
            apple = AppleClientConfig("a-svc", "a-team", "a-key", "-----BEGIN PRIVATE KEY-----\n-----END PRIVATE KEY-----"),
            allowedReturnOrigins = listOf("https://wordsparrow.example"),
        )

    private fun newWiring(linkGoogle: Boolean = false): Wiring {
        val users = InMemoryUserRepository()
        val sessions = InMemorySessionRepository()
        val userProviders = InMemoryUserProviderRepository()
        runBlocking {
            users.create(User(userId, DisplayName.of("Alice"), now, now))
            sessions.create(Session(sessionId, userId, now, now, null))
            if (linkGoogle) {
                userProviders.link(
                    UserProvider(
                        userId = userId,
                        provider = Provider.GOOGLE,
                        subject = Subject.of("google-sub-123"),
                        emailAtLink = "alice@example.com",
                        linkedAt = linkedAt,
                    ),
                )
            }
        }
        val clock = FixedClock(now)
        val whoAmI = WhoAmIUseCase(users, sessions, clock, Duration.ofDays(7))
        val getMe = GetMeUseCase(users, userProviders)
        return Wiring.forTesting(whoAmI = whoAmI, getMe = getMe)
    }

    @Test
    fun `no cookie returns 401`() =
        testApplication {
            application { module(newWiring(), testConfig) }
            val response = client.get("/v1/users/me")
            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        }

    @Test
    fun `valid session with no linked providers returns 200 with empty providers array`() =
        testApplication {
            application { module(newWiring(linkGoogle = false), testConfig) }
            val response =
                client.get("/v1/users/me") {
                    cookie(SessionCookies.NAME, sessionId.value.toString())
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = response.bodyAsText()
            assertThat(body).contains("\"id\":\"${userId.value}\"")
            assertThat(body).contains("\"displayName\":\"Alice\"")
            assertThat(body).contains("\"createdAt\":\"$now\"")
            assertThat(body).contains("\"providers\":[]")
        }

    @Test
    fun `valid session with linked Google provider returns 200 with provider entry`() =
        testApplication {
            application { module(newWiring(linkGoogle = true), testConfig) }
            val response =
                client.get("/v1/users/me") {
                    cookie(SessionCookies.NAME, sessionId.value.toString())
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = response.bodyAsText()
            assertThat(body).contains("\"provider\":\"google\"")
            assertThat(body).contains("\"linkedAt\":\"$linkedAt\"")
            assertThat(body).contains("\"emailOptIn\":true")
            assertThat(body).doesNotContain("\"providers\":[]")
        }
}
