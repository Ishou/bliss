package com.bliss.identity.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.identity.api.Wiring
import com.bliss.identity.api.auth.SessionCookies
import com.bliss.identity.api.config.AppleClientConfig
import com.bliss.identity.api.config.GoogleClientConfig
import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.api.module
import com.bliss.identity.application.ports.UserDeletedBroadcaster
import com.bliss.identity.application.usecases.DeleteUserUseCase
import com.bliss.identity.application.usecases.GetMeUseCase
import com.bliss.identity.application.usecases.UpdateMeUseCase
import com.bliss.identity.application.usecases.WhoAmIUseCase
import com.bliss.identity.domain.session.Session
import com.bliss.identity.domain.session.SessionId
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import com.bliss.identity.infrastructure.events.InMemoryUserDeletedBroadcaster
import com.bliss.identity.infrastructure.events.InMemoryUserRenamedBroadcaster
import com.bliss.identity.infrastructure.persistence.InMemorySessionRepository
import com.bliss.identity.infrastructure.persistence.InMemoryUserProviderRepository
import com.bliss.identity.infrastructure.persistence.InMemoryUserRepository
import com.bliss.identity.infrastructure.testdoubles.FixedClock
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.setCookie
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class PatchDeleteMeRouteTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
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

    private data class Fixture(
        val wiring: Wiring,
        val users: InMemoryUserRepository,
        val sessions: InMemorySessionRepository,
        val broadcaster: InMemoryUserDeletedBroadcaster,
    )

    private fun newFixture(): Fixture {
        val users = InMemoryUserRepository()
        val sessions = InMemorySessionRepository()
        val userProviders = InMemoryUserProviderRepository()
        val broadcaster = InMemoryUserDeletedBroadcaster()
        runBlocking {
            users.create(User(userId, DisplayName.of("Alice"), now, now))
            sessions.create(Session(sessionId, userId, now, now, null))
        }
        val clock = FixedClock(now)
        val whoAmI = WhoAmIUseCase(users, sessions, clock, Duration.ofDays(7))
        val getMe = GetMeUseCase(users, userProviders)
        val updateMe = UpdateMeUseCase(users, InMemoryUserRenamedBroadcaster(), clock)
        val deleteUser = DeleteUserUseCase(users, broadcaster, clock)
        return Fixture(
            wiring =
                Wiring.forTesting(
                    whoAmI = whoAmI,
                    getMe = getMe,
                    updateMe = updateMe,
                    deleteUser = deleteUser,
                ),
            users = users,
            sessions = sessions,
            broadcaster = broadcaster,
        )
    }

    @Test
    fun `patch without cookie returns 401`() =
        testApplication {
            application { module(newFixture().wiring, testConfig) }
            val response =
                client.patch("/v1/users/me") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"displayName":"Bob"}""")
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        }

    @Test
    fun `patch with malformed body returns 400 invalid_body`() =
        testApplication {
            application { module(newFixture().wiring, testConfig) }
            val response =
                client.patch("/v1/users/me") {
                    cookie(SessionCookies.NAME, sessionId.value.toString())
                    contentType(ContentType.Application.Json)
                    setBody("not-json")
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("invalid_body")
        }

    @Test
    fun `patch with empty display name returns 400 invalid_display_name`() =
        testApplication {
            application { module(newFixture().wiring, testConfig) }
            val response =
                client.patch("/v1/users/me") {
                    cookie(SessionCookies.NAME, sessionId.value.toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"displayName":""}""")
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("invalid_display_name")
        }

    @Test
    fun `patch with valid display name returns 200 with updated profile`() =
        testApplication {
            val fixture = newFixture()
            application { module(fixture.wiring, testConfig) }
            val response =
                client.patch("/v1/users/me") {
                    cookie(SessionCookies.NAME, sessionId.value.toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"displayName":"Bob"}""")
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = response.bodyAsText()
            assertThat(body).contains("\"displayName\":\"Bob\"")
            assertThat(body).contains("\"id\":\"${userId.value}\"")
            val persisted = runBlocking { fixture.users.findById(userId) }
            assertThat(persisted).isNotNull()
            assertThat(persisted!!.displayName.value).isEqualTo("Bob")
        }

    @Test
    fun `patch with absent displayName is a no-op and returns 200 with current profile`() =
        testApplication {
            val fixture = newFixture()
            application { module(fixture.wiring, testConfig) }
            val response =
                client.patch("/v1/users/me") {
                    cookie(SessionCookies.NAME, sessionId.value.toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"emailOptIn":true}""")
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = response.bodyAsText()
            assertThat(body).contains("\"displayName\":\"Alice\"")
        }

    @Test
    fun `delete without cookie returns 401`() =
        testApplication {
            application { module(newFixture().wiring, testConfig) }
            val response = client.delete("/v1/users/me")
            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        }

    @Test
    fun `delete with valid session returns 204 clears cookie hard-deletes user and broadcasts`() =
        testApplication {
            val fixture = newFixture()
            application { module(fixture.wiring, testConfig) }
            val response =
                client.delete("/v1/users/me") {
                    cookie(SessionCookies.NAME, sessionId.value.toString())
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.NoContent)
            val cleared = response.setCookie().firstOrNull { it.name == SessionCookies.NAME }
            assertThat(cleared).isNotNull()
            assertThat(cleared!!.maxAge).isEqualTo(0)
            val remaining = runBlocking { fixture.users.findById(userId) }
            assertThat(remaining).isNull()
            val captured = fixture.broadcaster.captured()
            assertThat(captured.size).isEqualTo(1)
            assertThat(captured[0].first).isEqualTo(userId)
            // Sessions are not explicitly revoked here; WhoAmIUseCase returns OrphanedSession
            // for any surviving session row once the user row is gone.
        }

    @Test
    fun `delete with failing broadcaster returns 503 and does not delete user or clear cookie`() =
        testApplication {
            val users = InMemoryUserRepository()
            val sessions = InMemorySessionRepository()
            runBlocking {
                users.create(User(userId, DisplayName.of("Alice"), now, now))
                sessions.create(Session(sessionId, userId, now, now, null))
            }
            val failingBroadcaster = UserDeletedBroadcaster { _, _ -> throw RuntimeException("downstream is down") }
            val clock = FixedClock(now)
            val whoAmI = WhoAmIUseCase(users, sessions, clock, Duration.ofDays(7))
            val deleteUser = DeleteUserUseCase(users, failingBroadcaster, clock)
            application { module(Wiring.forTesting(whoAmI = whoAmI, deleteUser = deleteUser), testConfig) }
            val response =
                client.delete("/v1/users/me") {
                    cookie(SessionCookies.NAME, sessionId.value.toString())
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
            assertThat(response.bodyAsText()).contains("broadcast_failed")
            assertThat(runBlocking { users.findById(userId) }).isNotNull()
            val cleared = response.setCookie().firstOrNull { it.name == SessionCookies.NAME }
            assertThat(cleared).isNull()
        }
}
