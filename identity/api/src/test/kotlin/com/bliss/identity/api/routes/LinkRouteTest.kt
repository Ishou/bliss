package com.bliss.identity.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.bliss.identity.api.Wiring
import com.bliss.identity.api.auth.SessionCookies
import com.bliss.identity.api.config.AppleClientConfig
import com.bliss.identity.api.config.GoogleClientConfig
import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.api.module
import com.bliss.identity.application.ports.ClientAuth
import com.bliss.identity.application.ports.OidcProviderConfig
import com.bliss.identity.application.ports.OidcResponseMode
import com.bliss.identity.application.usecases.BeginOidcLoginUseCase
import com.bliss.identity.application.usecases.WhoAmIUseCase
import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.auth.State
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.session.Session
import com.bliss.identity.domain.session.SessionId
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import com.bliss.identity.infrastructure.oidc.StaticOidcProviderConfigSource
import com.bliss.identity.infrastructure.persistence.InMemoryAuthAttemptRepository
import com.bliss.identity.infrastructure.persistence.InMemorySessionRepository
import com.bliss.identity.infrastructure.persistence.InMemoryUserRepository
import com.bliss.identity.infrastructure.testdoubles.FixedClock
import com.bliss.identity.infrastructure.testdoubles.FixedIdGenerator
import com.bliss.identity.infrastructure.testdoubles.FixedRandomFactory
import io.ktor.client.request.cookie
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class LinkRouteTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
    private val userId = UserId(UUID.randomUUID())
    private val sessionId = SessionId(UUID.randomUUID())

    private val testConfig =
        IdentityApiConfig(
            port = 0,
            publicHost = "auth.wordsparrow.example",
            google = GoogleClientConfig("g-client", "g-secret"),
            apple =
                AppleClientConfig(
                    "a-svc",
                    "a-team",
                    "a-key",
                    "-----BEGIN PRIVATE KEY-----\n-----END PRIVATE KEY-----",
                ),
            allowedReturnOrigins = listOf("https://wordsparrow.example"),
        )

    private val googleConfig =
        OidcProviderConfig(
            provider = Provider.GOOGLE,
            issuer = "https://accounts.google.com",
            audience = "g-client",
            clientId = "g-client",
            authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenUrl = "https://oauth2.googleapis.com/token",
            jwksUri = "https://www.googleapis.com/oauth2/v3/certs",
            redirectUri = "https://auth.wordsparrow.example/v1/auth/google/callback",
            responseMode = OidcResponseMode.QUERY,
            clientAuth = ClientAuth.Secret("g-secret"),
        )

    private data class WiringFixture(
        val wiring: Wiring,
        val attempts: InMemoryAuthAttemptRepository,
    )

    private fun newWiring(): WiringFixture {
        val users = InMemoryUserRepository()
        val sessions = InMemorySessionRepository()
        runBlocking {
            users.create(User(userId, DisplayName.of("Alice"), now, now))
            sessions.create(Session(sessionId, userId, now, now, null))
        }
        val whoAmI = WhoAmIUseCase(users, sessions, FixedClock(now), Duration.ofDays(7))
        val state = State.of("test-state-aaaabbbbccccddddeeeeffffgggghhhh")
        val pkce = PkceVerifier.of("test-pkce-verifier-abcdefghijklmnopqrstuvwxyz0123456")
        val attemptId = UUID.fromString("01890c5e-0000-7000-8000-00000000aa01")
        val attempts = InMemoryAuthAttemptRepository()
        val beginOidcLogin =
            BeginOidcLoginUseCase(
                configSource = StaticOidcProviderConfigSource(mapOf(Provider.GOOGLE to googleConfig)),
                randomFactory = FixedRandomFactory(states = listOf(state), pkceVerifiers = listOf(pkce)),
                idGenerator = FixedIdGenerator(authAttemptIds = listOf(attemptId)),
                attempts = attempts,
                clock = FixedClock(now),
                attemptTtl = Duration.ofMinutes(5),
            )
        return WiringFixture(
            wiring = Wiring.forTesting(beginOidcLogin = beginOidcLogin, whoAmI = whoAmI),
            attempts = attempts,
        )
    }

    @Test
    fun `no cookie returns 401`() =
        testApplication {
            application { module(newWiring().wiring, testConfig) }
            val response =
                client.post("/v1/users/me/providers/google/link") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"returnTo":"https://wordsparrow.example/account"}""")
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        }

    @Test
    fun `unknown provider returns 400`() =
        testApplication {
            application { module(newWiring().wiring, testConfig) }
            val response =
                client.post("/v1/users/me/providers/facebook/link") {
                    cookie(SessionCookies.NAME, sessionId.value.toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"returnTo":"https://wordsparrow.example/account"}""")
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        }

    @Test
    fun `missing body returns 400`() =
        testApplication {
            application { module(newWiring().wiring, testConfig) }
            val response =
                client.post("/v1/users/me/providers/google/link") {
                    cookie(SessionCookies.NAME, sessionId.value.toString())
                    contentType(ContentType.Application.Json)
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        }

    @Test
    fun `disallowed returnTo returns 400`() =
        testApplication {
            application { module(newWiring().wiring, testConfig) }
            val response =
                client.post("/v1/users/me/providers/google/link") {
                    cookie(SessionCookies.NAME, sessionId.value.toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"returnTo":"https://evil.example/"}""")
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        }

    @Test
    fun `valid request returns 200 with authorizeUrl`() =
        testApplication {
            val fixture = newWiring()
            application { module(fixture.wiring, testConfig) }
            val response =
                client.post("/v1/users/me/providers/google/link") {
                    cookie(SessionCookies.NAME, sessionId.value.toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"returnTo":"https://wordsparrow.example/account"}""")
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = response.bodyAsText()
            assertThat(body).contains("authorizeUrl")
            assertThat(body).contains("accounts.google.com")
            val state = State.of("test-state-aaaabbbbccccddddeeeeffffgggghhhh")
            val attempt = runBlocking { fixture.attempts.findByState(state) }
            assertThat(attempt?.linkToUserId).isEqualTo(userId)
        }
}
