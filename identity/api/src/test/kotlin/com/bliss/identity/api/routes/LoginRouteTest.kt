package com.bliss.identity.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.startsWith
import com.bliss.identity.api.Wiring
import com.bliss.identity.api.config.AppleClientConfig
import com.bliss.identity.api.config.GoogleClientConfig
import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.api.module
import com.bliss.identity.application.ports.ClientAuth
import com.bliss.identity.application.ports.OidcProviderConfig
import com.bliss.identity.application.ports.OidcResponseMode
import com.bliss.identity.application.usecases.BeginOidcLoginUseCase
import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.auth.State
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.infrastructure.oidc.StaticOidcProviderConfigSource
import com.bliss.identity.infrastructure.persistence.InMemoryAuthAttemptRepository
import com.bliss.identity.infrastructure.testdoubles.FixedClock
import com.bliss.identity.infrastructure.testdoubles.FixedIdGenerator
import com.bliss.identity.infrastructure.testdoubles.FixedRandomFactory
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class LoginRouteTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")

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

    private fun newBeginUseCase(): BeginOidcLoginUseCase {
        val state = State.of("test-state-aaaabbbbccccddddeeeeffffgggghhhh")
        val pkce = PkceVerifier.of("test-pkce-verifier-abcdefghijklmnopqrstuvwxyz0123456")
        val attemptId = UUID.fromString("01890c5e-0000-7000-8000-00000000aa01")
        return BeginOidcLoginUseCase(
            configSource = StaticOidcProviderConfigSource(mapOf(Provider.GOOGLE to googleConfig)),
            randomFactory = FixedRandomFactory(states = listOf(state), pkceVerifiers = listOf(pkce)),
            idGenerator = FixedIdGenerator(authAttemptIds = listOf(attemptId)),
            attempts = InMemoryAuthAttemptRepository(),
            clock = FixedClock(now),
            attemptTtl = Duration.ofMinutes(5),
        )
    }

    @Test
    fun `unknown provider returns 400`() =
        testApplication {
            val wiring = Wiring.forTesting(beginOidcLogin = newBeginUseCase())
            application { module(wiring, testConfig) }
            val client = createClient { followRedirects = false }
            val response = client.get("/v1/auth/facebook/login?return_to=https://wordsparrow.example/play")
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        }

    @Test
    fun `missing return_to returns 400`() =
        testApplication {
            val wiring = Wiring.forTesting(beginOidcLogin = newBeginUseCase())
            application { module(wiring, testConfig) }
            val client = createClient { followRedirects = false }
            val response = client.get("/v1/auth/google/login")
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        }

    @Test
    fun `disallowed return_to returns 400`() =
        testApplication {
            val wiring = Wiring.forTesting(beginOidcLogin = newBeginUseCase())
            application { module(wiring, testConfig) }
            val client = createClient { followRedirects = false }
            val response = client.get("/v1/auth/google/login?return_to=https://evil.example/")
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        }

    @Test
    fun `valid request returns 302 with Location and Cache-Control no-store`() =
        testApplication {
            val wiring = Wiring.forTesting(beginOidcLogin = newBeginUseCase())
            application { module(wiring, testConfig) }
            val client = createClient { followRedirects = false }
            val response = client.get("/v1/auth/google/login?return_to=https://wordsparrow.example/play")
            assertThat(response.status).isEqualTo(HttpStatusCode.Found)
            assertThat(response.headers["Location"]).isNotNull()
            assertThat(response.headers["Location"]!!).startsWith("https://accounts.google.com/o/oauth2/v2/auth")
            assertThat(response.headers["Cache-Control"] ?: "").contains("no-store")
        }
}
