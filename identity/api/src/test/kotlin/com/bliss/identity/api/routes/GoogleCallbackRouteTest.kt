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
import com.bliss.identity.application.ports.ClientAuth
import com.bliss.identity.application.ports.OidcCodeExchanger
import com.bliss.identity.application.ports.OidcExchangeError
import com.bliss.identity.application.ports.OidcExchangeResult
import com.bliss.identity.application.ports.OidcProviderConfig
import com.bliss.identity.application.ports.OidcResponseMode
import com.bliss.identity.application.usecases.CompleteOidcLoginUseCase
import com.bliss.identity.application.usecases.CompleteProviderLinkUseCase
import com.bliss.identity.domain.auth.AuthAttempt
import com.bliss.identity.domain.auth.AuthAttemptId
import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.auth.State
import com.bliss.identity.domain.oidc.OidcIdToken
import com.bliss.identity.domain.oidc.OidcVerificationError
import com.bliss.identity.domain.oidc.OidcVerifier
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.provider.Subject
import com.bliss.identity.domain.provider.UserProvider
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import com.bliss.identity.infrastructure.oidc.StaticOidcProviderConfigSource
import com.bliss.identity.infrastructure.persistence.InMemoryAuthAttemptRepository
import com.bliss.identity.infrastructure.persistence.InMemorySessionRepository
import com.bliss.identity.infrastructure.persistence.InMemoryUserProviderRepository
import com.bliss.identity.infrastructure.persistence.InMemoryUserRepository
import com.bliss.identity.infrastructure.testdoubles.FixedClock
import com.bliss.identity.infrastructure.testdoubles.FixedIdGenerator
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.setCookie
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GoogleCallbackRouteTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
    private val state = State.of("callback-state-aaaabbbbccccddddeeeeffffgggghhhh") // 47 chars
    private val pkce = PkceVerifier.of("verifier-abcdefghijklmnopqrstuvwxyz0123456789AB") // 47 chars
    private val attemptId = AuthAttemptId(UUID.fromString("01890c5e-0000-7000-8000-00000000aa01"))
    private val newUserId = UUID.fromString("01890c5e-0000-7000-8000-00000000bb01")
    private val newSessionId = UUID.fromString("01890c5e-0000-7000-8000-00000000cc01")
    private val returnTo = "https://wordsparrow.example/return"

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

    private fun happyAttempt(): AuthAttempt =
        AuthAttempt(
            id = attemptId,
            state = state,
            pkceVerifier = pkce,
            provider = Provider.GOOGLE,
            returnTo = returnTo,
            linkToUserId = null,
            expiresAt = now.plusSeconds(300),
        )

    private val happyExchanger: OidcCodeExchanger =
        OidcCodeExchanger { _, _, _, _ -> OidcExchangeResult(idToken = "raw-id-token") }

    private val happyVerifier: OidcVerifier =
        OidcVerifier { _, provider ->
            OidcIdToken(
                subject = Subject.of("google-sub-1"),
                issuer = provider.issuer,
                audience = provider.audience,
                issuedAt = now.minusSeconds(10),
                expiresAt = now.plusSeconds(3600),
                nonce = null,
            )
        }

    private data class Fixtures(
        val dispatcher: CallbackDispatcher,
        val attempts: InMemoryAuthAttemptRepository,
        val userProviders: InMemoryUserProviderRepository,
        val users: InMemoryUserRepository,
    )

    private fun newDispatcher(
        seedAttempt: Boolean = true,
        attempt: AuthAttempt = happyAttempt(),
        codeExchanger: OidcCodeExchanger = happyExchanger,
        verifier: OidcVerifier = happyVerifier,
        userProviders: InMemoryUserProviderRepository = InMemoryUserProviderRepository(),
        users: InMemoryUserRepository = InMemoryUserRepository(),
    ): Fixtures {
        val attempts = InMemoryAuthAttemptRepository()
        if (seedAttempt) runBlocking { attempts.create(attempt) }
        val configSource = StaticOidcProviderConfigSource(mapOf(Provider.GOOGLE to googleConfig))
        val clock = FixedClock(now)
        val login =
            CompleteOidcLoginUseCase(
                attempts = attempts,
                codeExchanger = codeExchanger,
                verifier = verifier,
                configSource = configSource,
                users = users,
                userProviders = userProviders,
                sessions = InMemorySessionRepository(),
                idGenerator = FixedIdGenerator(userIds = listOf(newUserId), sessionIds = listOf(newSessionId)),
                clock = clock,
            )
        val link =
            CompleteProviderLinkUseCase(
                attempts = attempts,
                codeExchanger = codeExchanger,
                verifier = verifier,
                configSource = configSource,
                userProviders = userProviders,
                clock = clock,
            )
        return Fixtures(CallbackDispatcher(attempts, login, link), attempts, userProviders, users)
    }

    @Test
    fun `provider error returns 400`() =
        testApplication {
            val fx = newDispatcher(seedAttempt = false)
            val wiring = Wiring.forTesting(callbackDispatcher = fx.dispatcher)
            application { module(wiring, testConfig) }
            val client = createClient { followRedirects = false }
            val response = client.get("/v1/auth/google/callback?error=access_denied")
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        }

    @Test
    fun `missing code returns 400`() =
        testApplication {
            val fx = newDispatcher(seedAttempt = false)
            val wiring = Wiring.forTesting(callbackDispatcher = fx.dispatcher)
            application { module(wiring, testConfig) }
            val client = createClient { followRedirects = false }
            val response = client.get("/v1/auth/google/callback?state=${state.value}")
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        }

    @Test
    fun `missing state returns 400`() =
        testApplication {
            val fx = newDispatcher(seedAttempt = false)
            val wiring = Wiring.forTesting(callbackDispatcher = fx.dispatcher)
            application { module(wiring, testConfig) }
            val client = createClient { followRedirects = false }
            val response = client.get("/v1/auth/google/callback?code=abc")
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        }

    @Test
    fun `unknown state maps to 400`() =
        testApplication {
            val fx = newDispatcher(seedAttempt = false)
            val wiring = Wiring.forTesting(callbackDispatcher = fx.dispatcher)
            application { module(wiring, testConfig) }
            val client = createClient { followRedirects = false }
            val response = client.get("/v1/auth/google/callback?code=abc&state=does-not-exist")
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        }

    @Test
    fun `valid callback issues session cookie and 302s to return_to`() =
        testApplication {
            val fx = newDispatcher(seedAttempt = true)
            val wiring = Wiring.forTesting(callbackDispatcher = fx.dispatcher)
            application { module(wiring, testConfig) }
            val client = createClient { followRedirects = false }
            val response = client.get("/v1/auth/google/callback?code=auth-code&state=${state.value}")
            assertThat(response.status).isEqualTo(HttpStatusCode.Found)
            assertThat(response.headers["Location"]).isEqualTo(returnTo)
            assertThat(response.headers["Cache-Control"] ?: "").contains("no-store")
            val cookies = response.setCookie()
            val session = cookies.firstOrNull { it.name == SessionCookies.NAME }
            assertThat(session).isNotNull()
            assertThat(session!!.value).isEqualTo(newSessionId.toString())
        }

    @Test
    fun `linking-mode valid callback 302s without issuing a session cookie`() =
        testApplication {
            val existingUserId = UserId(UUID.fromString("01890c5e-0000-7000-8000-00000000ee01"))
            val users = InMemoryUserRepository()
            runBlocking {
                users.create(
                    User(
                        id = existingUserId,
                        displayName = DisplayName.of("Joueur"),
                        createdAt = now.minusSeconds(60),
                        lastSeenAt = now.minusSeconds(60),
                    ),
                )
            }
            val userProviders = InMemoryUserProviderRepository()
            val linkingAttempt = happyAttempt().copy(linkToUserId = existingUserId)
            val fx =
                newDispatcher(
                    attempt = linkingAttempt,
                    userProviders = userProviders,
                    users = users,
                )
            val wiring = Wiring.forTesting(callbackDispatcher = fx.dispatcher)
            application { module(wiring, testConfig) }
            val client = createClient { followRedirects = false }
            val response = client.get("/v1/auth/google/callback?code=auth-code&state=${state.value}")
            assertThat(response.status).isEqualTo(HttpStatusCode.Found)
            assertThat(response.headers["Location"]).isEqualTo(returnTo)
            val cookies = response.setCookie()
            assertThat(cookies.firstOrNull { it.name == SessionCookies.NAME }).isNull()
            val linked = runBlocking { userProviders.findByProviderAndSubject(Provider.GOOGLE, Subject.of("google-sub-1")) }
            assertThat(linked).isNotNull()
            assertThat(linked!!.userId).isEqualTo(existingUserId)
        }

    @Test
    fun `expired state maps to 400`() =
        testApplication {
            val expired = happyAttempt().copy(expiresAt = now.minusSeconds(1))
            val fx = newDispatcher(attempt = expired)
            val wiring = Wiring.forTesting(callbackDispatcher = fx.dispatcher)
            application { module(wiring, testConfig) }
            val client = createClient { followRedirects = false }
            val response = client.get("/v1/auth/google/callback?code=auth-code&state=${state.value}")
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        }

    @Test
    fun `exchange rejected maps to 503`() =
        testApplication {
            val rejectingExchanger =
                OidcCodeExchanger { _, _, _, _ ->
                    throw OidcExchangeError.TokenEndpointRejected(Provider.GOOGLE, 400, RuntimeException("rejected"))
                }
            val fx = newDispatcher(codeExchanger = rejectingExchanger)
            val wiring = Wiring.forTesting(callbackDispatcher = fx.dispatcher)
            application { module(wiring, testConfig) }
            val client = createClient { followRedirects = false }
            val response = client.get("/v1/auth/google/callback?code=auth-code&state=${state.value}")
            assertThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
        }

    @Test
    fun `jwks unavailable maps to 503`() =
        testApplication {
            val unavailableVerifier =
                OidcVerifier { _, _ -> throw OidcVerificationError.JwksUnavailable(RuntimeException("network error")) }
            val fx = newDispatcher(verifier = unavailableVerifier)
            val wiring = Wiring.forTesting(callbackDispatcher = fx.dispatcher)
            application { module(wiring, testConfig) }
            val client = createClient { followRedirects = false }
            val response = client.get("/v1/auth/google/callback?code=auth-code&state=${state.value}")
            assertThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
        }

    @Test
    fun `invalid token signature maps to 500`() =
        testApplication {
            val badSigVerifier = OidcVerifier { _, _ -> throw OidcVerificationError.InvalidSignature() }
            val fx = newDispatcher(verifier = badSigVerifier)
            val wiring = Wiring.forTesting(callbackDispatcher = fx.dispatcher)
            application { module(wiring, testConfig) }
            val client = createClient { followRedirects = false }
            val response = client.get("/v1/auth/google/callback?code=auth-code&state=${state.value}")
            assertThat(response.status).isEqualTo(HttpStatusCode.InternalServerError)
        }

    @Test
    fun `orphaned link maps to 500`() =
        testApplication {
            val ghostUserId = UserId(UUID.fromString("01890c5e-0000-7000-8000-00000000dd01"))
            val seedProviders = InMemoryUserProviderRepository()
            runBlocking {
                seedProviders.link(
                    UserProvider(
                        userId = ghostUserId,
                        provider = Provider.GOOGLE,
                        subject = Subject.of("google-sub-1"),
                        emailAtLink = null,
                        linkedAt = now,
                    ),
                )
            }
            val fx = newDispatcher(userProviders = seedProviders)
            val wiring = Wiring.forTesting(callbackDispatcher = fx.dispatcher)
            application { module(wiring, testConfig) }
            val client = createClient { followRedirects = false }
            val response = client.get("/v1/auth/google/callback?code=auth-code&state=${state.value}")
            assertThat(response.status).isEqualTo(HttpStatusCode.InternalServerError)
        }
}
