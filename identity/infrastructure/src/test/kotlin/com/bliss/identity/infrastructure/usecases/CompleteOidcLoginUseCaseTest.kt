package com.bliss.identity.infrastructure.usecases

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.bliss.identity.application.ports.OidcCodeExchanger
import com.bliss.identity.application.ports.OidcExchangeResult
import com.bliss.identity.application.ports.OidcProviderConfig
import com.bliss.identity.application.ports.OidcResponseMode
import com.bliss.identity.application.usecases.CompleteOidcLoginCommand
import com.bliss.identity.application.usecases.CompleteOidcLoginError
import com.bliss.identity.application.usecases.CompleteOidcLoginUseCase
import com.bliss.identity.domain.auth.AuthAttempt
import com.bliss.identity.domain.auth.AuthAttemptId
import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.auth.State
import com.bliss.identity.domain.oidc.OidcIdToken
import com.bliss.identity.domain.oidc.OidcProvider
import com.bliss.identity.domain.oidc.OidcVerificationError
import com.bliss.identity.domain.oidc.OidcVerifier
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.provider.Subject
import com.bliss.identity.domain.provider.UserProvider
import com.bliss.identity.domain.session.SessionId
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CompleteOidcLoginUseCaseTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
    private val state = State.of("happy-state-aaaabbbbccccddddeeeeffffgggghhhh") // 44 chars
    private val pkce = PkceVerifier.of("verifier-abcdefghijklmnopqrstuvwxyz0123456789AB") // 47 chars
    private val attemptId = AuthAttemptId(UUID.fromString("01890c5e-0000-7000-8000-00000000aa01"))
    private val newUserId = UUID.fromString("01890c5e-0000-7000-8000-00000000bb01")
    private val newSessionId = UUID.fromString("01890c5e-0000-7000-8000-00000000cc01")

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

    private fun attempt(linkTo: UserId? = null): AuthAttempt =
        AuthAttempt(
            id = attemptId,
            state = state,
            pkceVerifier = pkce,
            provider = Provider.GOOGLE,
            returnTo = "https://wordsparrow.example/return",
            linkToUserId = linkTo,
            expiresAt = now.plusSeconds(300),
        )

    private val happyExchanger: OidcCodeExchanger =
        OidcCodeExchanger { _, _, _, _ -> OidcExchangeResult(idToken = "raw-id-token") }

    private fun happyVerifier(subject: String = "google-sub-1") =
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

    private data class RepositoriesBundle(
        val attempts: InMemoryAuthAttemptRepository,
        val users: InMemoryUserRepository,
        val userProviders: InMemoryUserProviderRepository,
        val sessions: InMemorySessionRepository,
    )

    private fun newUseCase(
        attempts: InMemoryAuthAttemptRepository = InMemoryAuthAttemptRepository(),
        users: InMemoryUserRepository = InMemoryUserRepository(),
        userProviders: InMemoryUserProviderRepository = InMemoryUserProviderRepository(),
        sessions: InMemorySessionRepository = InMemorySessionRepository(),
        codeExchanger: OidcCodeExchanger = happyExchanger,
        verifier: OidcVerifier = happyVerifier(),
        clock: FixedClock = FixedClock(now),
        userIds: List<UUID> = listOf(newUserId),
        sessionIds: List<UUID> = listOf(newSessionId),
    ): Pair<CompleteOidcLoginUseCase, RepositoriesBundle> {
        val sut =
            CompleteOidcLoginUseCase(
                attempts = attempts,
                codeExchanger = codeExchanger,
                verifier = verifier,
                configSource = StaticOidcProviderConfigSource(mapOf(Provider.GOOGLE to googleConfig)),
                users = users,
                userProviders = userProviders,
                sessions = sessions,
                idGenerator = FixedIdGenerator(userIds = userIds, sessionIds = sessionIds),
                clock = clock,
            )
        return sut to RepositoriesBundle(attempts, users, userProviders, sessions)
    }

    @Test
    fun `unknown state throws UnknownState`() =
        runTest {
            val (sut, _) = newUseCase()
            assertFailure {
                sut.execute(
                    CompleteOidcLoginCommand("nope-but-long-enough-to-pass-State-of-validation", "code-1"),
                )
            }.isInstanceOf(CompleteOidcLoginError.UnknownState::class)
        }

    @Test
    fun `blank state throws UnknownState`() =
        runTest {
            val (sut, _) = newUseCase()
            assertFailure { sut.execute(CompleteOidcLoginCommand("", "code-1")) }
                .isInstanceOf(CompleteOidcLoginError.UnknownState::class)
        }

    @Test
    fun `expired state throws StateExpired and removes the attempt`() =
        runTest {
            val attempts = InMemoryAuthAttemptRepository()
            attempts.create(attempt())
            val expiredClock = FixedClock(now.plusSeconds(600))
            val (sut, bundle) = newUseCase(attempts = attempts, clock = expiredClock)
            assertFailure { sut.execute(CompleteOidcLoginCommand(state.value, "code-1")) }
                .isInstanceOf(CompleteOidcLoginError.StateExpired::class)
            assertThat(bundle.attempts.findByState(state)).isNull()
        }

    @Test
    fun `linking-mode attempt throws LinkingNotSupportedHere and leaves the attempt in place`() =
        runTest {
            val attempts = InMemoryAuthAttemptRepository()
            attempts.create(attempt(linkTo = UserId(UUID.randomUUID())))
            val (sut, bundle) = newUseCase(attempts = attempts)
            assertFailure { sut.execute(CompleteOidcLoginCommand(state.value, "code-1")) }
                .isInstanceOf(CompleteOidcLoginError.LinkingNotSupportedHere::class)
            assertThat(bundle.attempts.findByState(state)).isNotNull()
        }

    @Test
    fun `first-time sign-in creates user, provider link, and session`() =
        runTest {
            val attempts = InMemoryAuthAttemptRepository()
            attempts.create(attempt())
            val (sut, bundle) = newUseCase(attempts = attempts)
            val result = sut.execute(CompleteOidcLoginCommand(state.value, "code-1"))
            assertThat(result.userId).isEqualTo(UserId(newUserId))
            assertThat(result.sessionId).isEqualTo(SessionId(newSessionId))
            assertThat(result.returnTo).isEqualTo("https://wordsparrow.example/return")
            assertThat(bundle.users.findById(UserId(newUserId))).isNotNull()
            val link =
                bundle.userProviders.findByProviderAndSubject(Provider.GOOGLE, Subject.of("google-sub-1"))
            assertThat(link).isNotNull()
            assertThat(link!!.userId).isEqualTo(UserId(newUserId))
            val session = bundle.sessions.findById(SessionId(newSessionId))
            assertThat(session).isNotNull()
            assertThat(session!!.userId).isEqualTo(UserId(newUserId))
            assertThat(session.isActive).isTrue()
            assertThat(bundle.attempts.findByState(state)).isNull()
        }

    @Test
    fun `returning sign-in reuses the existing user and only creates a new session`() =
        runTest {
            val attempts = InMemoryAuthAttemptRepository()
            attempts.create(attempt())
            val existingUserId = UserId(UUID.fromString("01890c5e-0000-7000-8000-00000000dd01"))
            val userRepo =
                InMemoryUserRepository().apply {
                    create(
                        User(
                            id = existingUserId,
                            displayName = DisplayName.of("Existing"),
                            createdAt = now.minusSeconds(86_400),
                            lastSeenAt = now.minusSeconds(86_400),
                        ),
                    )
                }
            val linkRepo =
                InMemoryUserProviderRepository().apply {
                    link(
                        UserProvider(
                            userId = existingUserId,
                            provider = Provider.GOOGLE,
                            subject = Subject.of("google-sub-1"),
                            emailAtLink = null,
                            linkedAt = now.minusSeconds(86_400),
                        ),
                    )
                }
            val (sut, bundle) =
                newUseCase(
                    attempts = attempts,
                    users = userRepo,
                    userProviders = linkRepo,
                    userIds = emptyList(),
                    sessionIds = listOf(newSessionId),
                )
            val result = sut.execute(CompleteOidcLoginCommand(state.value, "code-1"))
            assertThat(result.userId).isEqualTo(existingUserId)
            assertThat(result.sessionId).isEqualTo(SessionId(newSessionId))
            assertThat(bundle.users.findById(existingUserId)!!.lastSeenAt).isEqualTo(now)
        }

    @Test
    fun `orphaned user_providers link throws OrphanedLink`() =
        runTest {
            val attempts = InMemoryAuthAttemptRepository()
            attempts.create(attempt())
            val orphanUserId = UserId(UUID.fromString("01890c5e-0000-7000-8000-00000000ee01"))
            val linkRepo =
                InMemoryUserProviderRepository().apply {
                    link(
                        UserProvider(
                            userId = orphanUserId,
                            provider = Provider.GOOGLE,
                            subject = Subject.of("google-sub-1"),
                            emailAtLink = null,
                            linkedAt = now.minusSeconds(86_400),
                        ),
                    )
                }
            // users repo is empty - the link points at a nonexistent user.
            val (sut, bundle) =
                newUseCase(
                    attempts = attempts,
                    userProviders = linkRepo,
                    userIds = emptyList(),
                    sessionIds = emptyList(),
                )
            val failure = assertFailure { sut.execute(CompleteOidcLoginCommand(state.value, "code-1")) }
            failure.isInstanceOf(CompleteOidcLoginError.OrphanedLink::class)
            // attempt was consumed before the orphan detection (after the linking-mode check)
            assertThat(bundle.attempts.findByState(state)).isNull()
        }

    @Test
    fun `OidcVerificationError from the verifier propagates`() =
        runTest {
            val attempts = InMemoryAuthAttemptRepository()
            attempts.create(attempt())
            val explodingVerifier = OidcVerifier { _, _ -> throw OidcVerificationError.InvalidSignature() }
            val (sut, bundle) = newUseCase(attempts = attempts, verifier = explodingVerifier)
            assertFailure { sut.execute(CompleteOidcLoginCommand(state.value, "code-1")) }
                .isInstanceOf(OidcVerificationError.InvalidSignature::class)
            assertThat(bundle.attempts.findByState(state)).isNull()
        }
}
