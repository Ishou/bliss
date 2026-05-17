package com.bliss.identity.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.startsWith
import com.bliss.identity.api.Wiring
import com.bliss.identity.api.auth.SessionCookies
import com.bliss.identity.api.config.AppleClientConfig
import com.bliss.identity.api.config.GoogleClientConfig
import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.api.module
import com.bliss.identity.application.usecases.WhoAmIUseCase
import com.bliss.identity.domain.session.Session
import com.bliss.identity.domain.session.SessionId
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import com.bliss.identity.infrastructure.persistence.InMemorySessionRepository
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

class WhoAmIRouteTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
    private val userId = UserId(UUID.randomUUID())
    private val sessionId = SessionId(UUID.randomUUID())

    private fun newWiring(): Wiring {
        val users = InMemoryUserRepository()
        val sessions = InMemorySessionRepository()
        runBlocking {
            users.create(User(userId, DisplayName.of("Alice"), now, now))
            sessions.create(Session(sessionId, userId, now, now, null))
        }
        val whoAmI = WhoAmIUseCase(users, sessions, FixedClock(now), Duration.ofDays(7))
        return Wiring.forTesting(whoAmI = whoAmI)
    }

    private val testConfig =
        IdentityApiConfig(
            port = 0,
            publicHost = "localhost",
            google = GoogleClientConfig("g-client", "g-secret"),
            apple = AppleClientConfig("a-svc", "a-team", "a-key", "-----BEGIN PRIVATE KEY-----\n-----END PRIVATE KEY-----"),
            allowedReturnOrigins = listOf("https://wordsparrow.example"),
        )

    @Test
    fun `no cookie returns 401 with problem json`() =
        testApplication {
            application { module(newWiring(), testConfig) }
            val response = client.get("/v1/auth/whoami")
            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
            val contentType = response.headers["Content-Type"]
            assertThat(contentType).isNotNull()
            assertThat(contentType!!).startsWith("application/problem+json")
        }

    @Test
    fun `invalid cookie UUID returns 401`() =
        testApplication {
            application { module(newWiring(), testConfig) }
            val response =
                client.get("/v1/auth/whoami") {
                    cookie(SessionCookies.NAME, "not-a-uuid")
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        }

    @Test
    fun `unknown session returns 401`() =
        testApplication {
            application { module(newWiring(), testConfig) }
            val response =
                client.get("/v1/auth/whoami") {
                    cookie(SessionCookies.NAME, UUID.randomUUID().toString())
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        }

    @Test
    fun `valid session returns 200 with userId and displayName`() =
        testApplication {
            application { module(newWiring(), testConfig) }
            val response =
                client.get("/v1/auth/whoami") {
                    cookie(SessionCookies.NAME, sessionId.value.toString())
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = response.bodyAsText()
            assertThat(body).contains("\"userId\":\"${userId.value}\"")
            assertThat(body).contains("\"displayName\":\"Alice\"")
        }
}
