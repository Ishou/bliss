package com.bliss.identity.infrastructure.usecases

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import com.bliss.identity.application.ports.ClientAuth
import com.bliss.identity.application.ports.OidcCodeExchanger
import com.bliss.identity.application.ports.OidcExchangeResult
import com.bliss.identity.application.ports.OidcProviderConfig
import com.bliss.identity.application.ports.OidcResponseMode
import com.bliss.identity.application.usecases.CompleteProviderLinkCommand
import com.bliss.identity.application.usecases.CompleteProviderLinkError
import com.bliss.identity.application.usecases.CompleteProviderLinkUseCase
import com.bliss.identity.domain.auth.AuthAttempt
import com.bliss.identity.domain.auth.AuthAttemptId
import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.auth.State
import com.bliss.identity.domain.oidc.OidcIdToken
import com.bliss.identity.domain.oidc.OidcProvider
import com.bliss.identity.domain.oidc.OidcVerifier
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.provider.Subject
import com.bliss.identity.domain.provider.UserProvider
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import com.bliss.identity.infrastructure.oidc.StaticOidcProviderConfigSource
import com.bliss.identity.infrastructure.persistence.InMemoryAuthAttemptRepository
import com.bliss.identity.infrastructure.persistence.InMemoryUserProviderRepository
import com.bliss.identity.infrastructure.persistence.InMemoryUserRepository
import com.bliss.identity.infrastructure.testdoubles.FixedClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class CompleteProviderLinkUseCaseTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
    private val state = State.of("link-state-aaaabbbbccccddddeeeeffffgggghhhh") // 44 chars
    private val pkce = PkceVerifier.of("verifier-abcdefghijklmnopqrstuvwxyz0123456789AB") // 47 chars
    private val attemptId = AuthAttemptId(UUID.fromString("01890c5e-0000-7000-8000-00000000aa10"))
    private val linkUserId = UserId(UUID.fromString("01890c5e-0000-7000-8000-00000000bb10"))

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
            clientAuth = ClientAuth.Secret("test-secret"),
        )

    private fun linkAttempt(linkTo: UserId = linkUserId): AuthAttempt =
        AuthAttempt(
            id = attemptId,
            state = state,
            pkceVerifier = pkce,
            provider = Provider.GOOGLE,
            returnTo = "https://wordsparrow.example/return",
            linkToUserId = linkTo,
            expiresAt = now.plusSeconds(300),
        )

    private fun signInAttempt(): AuthAttempt =
        AuthAttempt(
            id = attemptId,
            state = state,
            pkceVerifier = pkce,
            provider = Provider.GOOGLE,
            returnTo = "https://wordsparrow.example/return",
            linkToUserId = null,
            expiresAt = now.plusSeconds(300),
        )

    private val happyExchanger: OidcCodeExchanger =
        OidcCodeExchanger { _, _, _, _ -> OidcExchangeResult(idToken = "raw-id-token") }

    private fun happyVerifier(subject: String = "google-sub-link-1") =
        OidcVerifier { _, provider: OidcProvider ->
            OidcIdToken(
                subject = Subject.of(subject),
                issuer = provider.issuer,
                audience = provider.audience,
                issuedAt = now.minusSeconds(10),
                expiresAt = now.plusSeconds(3600),
                nonce = null,
            )
        }

    private fun newUseCase(
        attempts: InMemoryAuthAttemptRepository = InMemoryAuthAttemptRepository(),
        users: InMemoryUserRepository = InMemoryUserRepository(),
        userProviders: InMemoryUserProviderRepository = InMemoryUserProviderRepository(),
        codeExchanger: OidcCodeExchanger = happyExchanger,
        verifier: OidcVerifier = happyVerifier(),
        clock: FixedClock = FixedClock(now),
    ): CompleteProviderLinkUseCase =
        CompleteProviderLinkUseCase(
            attempts = attempts,
            codeExchanger = codeExchanger,
            verifier = verifier,
            configSource = StaticOidcProviderConfigSource(mapOf(Provider.GOOGLE to googleConfig)),
            userProviders = userProviders,
            users = users,
            clock = clock,
        )

    @Test
    fun `unknown state throws UnknownState`() =
        runTest {
            val sut = newUseCase()
            assertFailure {
                sut.execute(CompleteProviderLinkCommand("nope-but-long-enough-to-pass-State-of-validation", "code-1"))
            }.isInstanceOf(CompleteProviderLinkError.UnknownState::class)
        }

    @Test
    fun `expired state throws StateExpired and removes the attempt`() =
        runTest {
            val attempts = InMemoryAuthAttemptRepository()
            attempts.create(linkAttempt())
            val expiredClock = FixedClock(now.plusSeconds(600))
            val sut = newUseCase(attempts = attempts, clock = expiredClock)
            assertFailure { sut.execute(CompleteProviderLinkCommand(state.value, "code-1")) }
                .isInstanceOf(CompleteProviderLinkError.StateExpired::class)
            assertThat(attempts.findByState(state)).isNull()
        }

    @Test
    fun `sign-in-mode attempt throws NotLinkingMode and leaves the attempt in place`() =
        runTest {
            val attempts = InMemoryAuthAttemptRepository()
            attempts.create(signInAttempt())
            val sut = newUseCase(attempts = attempts)
            assertFailure { sut.execute(CompleteProviderLinkCommand(state.value, "code-1")) }
                .isInstanceOf(CompleteProviderLinkError.NotLinkingMode::class)
            assertThat(attempts.findByState(state)).isNotNull()
        }

    @Test
    fun `happy path inserts provider link and returns`() =
        runTest {
            val attempts = InMemoryAuthAttemptRepository()
            attempts.create(linkAttempt())
            val users = InMemoryUserRepository()
            users.create(User(linkUserId, DisplayName.of("Alice"), now.minusSeconds(86_400), now.minusSeconds(86_400)))
            val userProviders = InMemoryUserProviderRepository()
            val sut = newUseCase(attempts = attempts, users = users, userProviders = userProviders)
            sut.execute(CompleteProviderLinkCommand(state.value, "code-1"))
            val link = userProviders.findByProviderAndSubject(Provider.GOOGLE, Subject.of("google-sub-link-1"))
            assertThat(link).isNotNull()
            assertThat(link!!.userId).isEqualTo(linkUserId)
            assertThat(attempts.findByState(state)).isNull()
        }

    @Test
    fun `subject already linked to a different user throws LinkConflict`() =
        runTest {
            val attempts = InMemoryAuthAttemptRepository()
            attempts.create(linkAttempt())
            val otherUserId = UserId(UUID.fromString("01890c5e-0000-7000-8000-00000000cc10"))
            val userProviders =
                InMemoryUserProviderRepository().apply {
                    link(
                        UserProvider(
                            userId = otherUserId,
                            provider = Provider.GOOGLE,
                            subject = Subject.of("google-sub-link-1"),
                            emailAtLink = null,
                            linkedAt = now.minusSeconds(86_400),
                        ),
                    )
                }
            val users = InMemoryUserRepository()
            users.create(User(linkUserId, DisplayName.of("Alice"), now.minusSeconds(86_400), now.minusSeconds(86_400)))
            val sut = newUseCase(attempts = attempts, users = users, userProviders = userProviders)
            val failure = assertFailure { sut.execute(CompleteProviderLinkCommand(state.value, "code-1")) }
            failure.isInstanceOf(CompleteProviderLinkError.LinkConflict::class)
            failure.given { thrown ->
                assertThat((thrown as CompleteProviderLinkError.LinkConflict).owningUserId).isEqualTo(otherUserId)
            }
            assertThat(attempts.findByState(state)).isNull()
        }

    @Test
    fun `CancellationException from code exchanger propagates unwrapped`() =
        runTest {
            val attempts = InMemoryAuthAttemptRepository()
            attempts.create(linkAttempt())
            val users = InMemoryUserRepository()
            users.create(User(linkUserId, DisplayName.of("Alice"), now.minusSeconds(86_400), now.minusSeconds(86_400)))
            val cancellation = CancellationException("cancelled")
            val cancellingExchanger = OidcCodeExchanger { _, _, _, _ -> throw cancellation }
            val sut = newUseCase(attempts = attempts, users = users, codeExchanger = cancellingExchanger)
            val failure = assertFailure { sut.execute(CompleteProviderLinkCommand(state.value, "code-1")) }
            failure.isInstanceOf(CancellationException::class)
            failure.given { thrown -> assertThat(thrown).isSameInstanceAs(cancellation) }
        }
}
