package com.bliss.identity.infrastructure.usecases

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.startsWith
import com.bliss.identity.application.ports.OidcProviderConfig
import com.bliss.identity.application.ports.OidcResponseMode
import com.bliss.identity.application.usecases.BeginOidcLoginCommand
import com.bliss.identity.application.usecases.BeginOidcLoginUseCase
import com.bliss.identity.domain.auth.AuthAttemptId
import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.auth.State
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.user.UserId
import com.bliss.identity.infrastructure.oidc.StaticOidcProviderConfigSource
import com.bliss.identity.infrastructure.persistence.InMemoryAuthAttemptRepository
import com.bliss.identity.infrastructure.testdoubles.FixedClock
import com.bliss.identity.infrastructure.testdoubles.FixedIdGenerator
import com.bliss.identity.infrastructure.testdoubles.FixedRandomFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class BeginOidcLoginUseCaseTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
    private val attemptTtl: Duration = Duration.ofMinutes(5)
    private val state = State.of("test-state-aaaabbbbccccddddeeeeffffgggghhhh")
    private val pkce = PkceVerifier.of("test-pkce-verifier-abcdefghijklmnopqrstuvwxyz0123456")
    private val attemptId = UUID.fromString("01890c5e-0000-7000-8000-00000000aa01")

    private val googleConfig =
        OidcProviderConfig(
            provider = Provider.GOOGLE,
            issuer = "https://accounts.google.com",
            audience = "google-client",
            clientId = "google-client",
            authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenUrl = "https://oauth2.googleapis.com/token",
            jwksUri = "https://www.googleapis.com/oauth2/v3/certs",
            redirectUri = "https://auth.wordsparrow.io/v1/auth/google/callback",
            responseMode = OidcResponseMode.QUERY,
        )
    private val appleConfig =
        googleConfig.copy(
            provider = Provider.APPLE,
            issuer = "https://appleid.apple.com",
            audience = "apple-service-id",
            clientId = "apple-service-id",
            authorizeUrl = "https://appleid.apple.com/auth/authorize",
            tokenUrl = "https://appleid.apple.com/auth/token",
            jwksUri = "https://appleid.apple.com/auth/keys",
            redirectUri = "https://auth.wordsparrow.io/v1/auth/apple/callback",
            responseMode = OidcResponseMode.FORM_POST,
        )

    private fun useCase(): BeginOidcLoginUseCase =
        BeginOidcLoginUseCase(
            configSource =
                StaticOidcProviderConfigSource(
                    mapOf(Provider.GOOGLE to googleConfig, Provider.APPLE to appleConfig),
                ),
            randomFactory = FixedRandomFactory(states = listOf(state), pkceVerifiers = listOf(pkce)),
            idGenerator = FixedIdGenerator(authAttemptIds = listOf(attemptId)),
            attempts = InMemoryAuthAttemptRepository(),
            clock = FixedClock(now),
            attemptTtl = attemptTtl,
        )

    @Test
    fun `returns the authorize URL for Google`() =
        runTest {
            val result = useCase().execute(BeginOidcLoginCommand(Provider.GOOGLE, "https://wordsparrow.example/return"))
            assertThat(result.authorizeUrl).startsWith(googleConfig.authorizeUrl + "?")
            assertThat(result.authorizeUrl).contains("response_type=code")
            assertThat(result.authorizeUrl).contains("client_id=google-client")
            assertThat(result.authorizeUrl).contains("scope=openid")
            assertThat(result.authorizeUrl).contains("state=${state.value}")
            assertThat(result.authorizeUrl).contains("code_challenge=${pkce.challenge()}")
            assertThat(result.authorizeUrl).contains("code_challenge_method=S256")
            assertThat(result.authorizeUrl).contains("response_mode=query")
        }

    @Test
    fun `Apple URL uses response_mode form_post`() =
        runTest {
            val result = useCase().execute(BeginOidcLoginCommand(Provider.APPLE, "https://wordsparrow.example/return"))
            assertThat(result.authorizeUrl).startsWith(appleConfig.authorizeUrl + "?")
            assertThat(result.authorizeUrl).contains("response_mode=form_post")
        }

    @Test
    fun `persists an AuthAttempt that the same state can find`() =
        runTest {
            val attempts = InMemoryAuthAttemptRepository()
            val sut =
                BeginOidcLoginUseCase(
                    configSource = StaticOidcProviderConfigSource(mapOf(Provider.GOOGLE to googleConfig)),
                    randomFactory = FixedRandomFactory(states = listOf(state), pkceVerifiers = listOf(pkce)),
                    idGenerator = FixedIdGenerator(authAttemptIds = listOf(attemptId)),
                    attempts = attempts,
                    clock = FixedClock(now),
                    attemptTtl = attemptTtl,
                )
            sut.execute(BeginOidcLoginCommand(Provider.GOOGLE, "https://wordsparrow.example/return"))
            val stored = attempts.findByState(state)
            assertThat(stored).isNotNull()
            assertThat(stored!!.id).isEqualTo(AuthAttemptId(attemptId))
            assertThat(stored.provider).isEqualTo(Provider.GOOGLE)
            assertThat(stored.returnTo).isEqualTo("https://wordsparrow.example/return")
            assertThat(stored.pkceVerifier).isEqualTo(pkce)
            assertThat(stored.expiresAt).isEqualTo(now.plus(attemptTtl))
            assertThat(stored.linkToUserId).isEqualTo(null)
        }

    @Test
    fun `URL-encodes the redirect_uri`() =
        runTest {
            val result = useCase().execute(BeginOidcLoginCommand(Provider.GOOGLE, "https://wordsparrow.example/return"))
            assertThat(result.authorizeUrl).contains(
                "redirect_uri=https%3A%2F%2Fauth.wordsparrow.io%2Fv1%2Fauth%2Fgoogle%2Fcallback",
            )
        }

    @Test
    fun `persists linkToUserId when provided`() =
        runTest {
            val userId = UserId(UUID.fromString("01890c5e-0000-7000-8000-00000000bb01"))
            val attempts = InMemoryAuthAttemptRepository()
            val sut =
                BeginOidcLoginUseCase(
                    configSource = StaticOidcProviderConfigSource(mapOf(Provider.GOOGLE to googleConfig)),
                    randomFactory = FixedRandomFactory(states = listOf(state), pkceVerifiers = listOf(pkce)),
                    idGenerator = FixedIdGenerator(authAttemptIds = listOf(attemptId)),
                    attempts = attempts,
                    clock = FixedClock(now),
                    attemptTtl = attemptTtl,
                )
            sut.execute(BeginOidcLoginCommand(Provider.GOOGLE, "https://wordsparrow.example/return"), linkToUserId = userId)
            val stored = attempts.findByState(state)
            assertThat(stored).isNotNull()
            assertThat(stored!!.linkToUserId).isEqualTo(userId)
        }
}
