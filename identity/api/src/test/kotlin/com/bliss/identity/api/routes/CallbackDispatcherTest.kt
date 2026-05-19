package com.bliss.identity.api.routes

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.bliss.identity.application.ports.ClientAuth
import com.bliss.identity.application.ports.OidcCodeExchanger
import com.bliss.identity.application.ports.OidcExchangeResult
import com.bliss.identity.application.ports.OidcProviderConfig
import com.bliss.identity.application.ports.OidcResponseMode
import com.bliss.identity.application.usecases.CompleteOidcLoginError
import com.bliss.identity.application.usecases.CompleteOidcLoginUseCase
import com.bliss.identity.application.usecases.CompleteProviderLinkUseCase
import com.bliss.identity.domain.auth.AuthAttempt
import com.bliss.identity.domain.auth.AuthAttemptId
import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.auth.State
import com.bliss.identity.domain.oidc.OidcIdToken
import com.bliss.identity.domain.oidc.OidcVerifier
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.provider.Subject
import com.bliss.identity.domain.user.UserId
import com.bliss.identity.infrastructure.oidc.StaticOidcProviderConfigSource
import com.bliss.identity.infrastructure.persistence.InMemoryAuthAttemptRepository
import com.bliss.identity.infrastructure.persistence.InMemorySessionRepository
import com.bliss.identity.infrastructure.persistence.InMemoryUserProviderRepository
import com.bliss.identity.infrastructure.persistence.InMemoryUserRepository
import com.bliss.identity.infrastructure.testdoubles.FixedClock
import com.bliss.identity.infrastructure.testdoubles.FixedIdGenerator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class CallbackDispatcherTest {
    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
    private val state = State.of("callback-state-aaaabbbbccccddddeeeeffffgggghhhh")
    private val pkce = PkceVerifier.of("verifier-abcdefghijklmnopqrstuvwxyz0123456789AB")
    private val attemptId = AuthAttemptId(UUID.fromString("01890c5e-0000-7000-8000-00000000aa10"))
    private val newUserId = UUID.fromString("01890c5e-0000-7000-8000-00000000bb10")
    private val newSessionId = UUID.fromString("01890c5e-0000-7000-8000-00000000cc10")
    private val existingUserId = UserId(UUID.fromString("01890c5e-0000-7000-8000-00000000ee10"))
    private val returnTo = "https://wordsparrow.example/return"

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

    private fun happyAttempt(linkToUserId: UserId? = null) =
        AuthAttempt(
            id = attemptId,
            state = state,
            pkceVerifier = pkce,
            provider = Provider.GOOGLE,
            returnTo = returnTo,
            linkToUserId = linkToUserId,
            expiresAt = now.plusSeconds(300),
        )

    private fun buildDispatcher(
        seedAttempt: AuthAttempt? = happyAttempt(),
        codeExchanger: OidcCodeExchanger = happyExchanger,
        verifier: OidcVerifier = happyVerifier,
        userProviders: InMemoryUserProviderRepository = InMemoryUserProviderRepository(),
        users: InMemoryUserRepository = InMemoryUserRepository(),
    ): CallbackDispatcher {
        val attempts = InMemoryAuthAttemptRepository()
        if (seedAttempt != null) runBlocking { attempts.create(seedAttempt) }
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
        return CallbackDispatcher(attempts, login, link)
    }

    @Test
    fun `absent attempt routes to login use case and propagates UnknownState`() {
        val dispatcher = buildDispatcher(seedAttempt = null)
        assertThrows<CompleteOidcLoginError.UnknownState> {
            runBlocking { dispatcher.dispatch(state = state.value, code = "any-code") }
        }
    }

    @Test
    fun `malformed state string is swallowed by peek and routes to login as UnknownState`() {
        val dispatcher = buildDispatcher(seedAttempt = happyAttempt(linkToUserId = existingUserId))
        assertThrows<CompleteOidcLoginError.UnknownState> {
            runBlocking { dispatcher.dispatch(state = "too-short", code = "any-code") }
        }
    }

    @Test
    fun `login attempt with null linkToUserId returns LoggedIn`() {
        val dispatcher = buildDispatcher(seedAttempt = happyAttempt(linkToUserId = null))
        val result = runBlocking { dispatcher.dispatch(state = state.value, code = "auth-code") }
        assertThat(result).isInstanceOf(CallbackDispatcher.Result.LoggedIn::class)
        val loggedIn = result as CallbackDispatcher.Result.LoggedIn
        assertThat(loggedIn.sessionId.value).isEqualTo(newSessionId)
        assertThat(loggedIn.returnTo).isEqualTo(returnTo)
    }

    @Test
    fun `linking attempt with non-null linkToUserId returns Linked`() {
        val dispatcher = buildDispatcher(seedAttempt = happyAttempt(linkToUserId = existingUserId))
        val result = runBlocking { dispatcher.dispatch(state = state.value, code = "auth-code") }
        assertThat(result).isInstanceOf(CallbackDispatcher.Result.Linked::class)
        val linked = result as CallbackDispatcher.Result.Linked
        assertThat(linked.returnTo).isEqualTo(returnTo)
    }
}
