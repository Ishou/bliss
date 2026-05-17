package com.bliss.identity.application.usecases

import com.bliss.identity.application.ports.AuthAttemptRepository
import com.bliss.identity.application.ports.Clock
import com.bliss.identity.application.ports.IdGenerator
import com.bliss.identity.application.ports.OidcProviderConfig
import com.bliss.identity.application.ports.OidcProviderConfigSource
import com.bliss.identity.application.ports.RandomFactory
import com.bliss.identity.domain.auth.AuthAttempt
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.user.UserId
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

data class BeginOidcLoginCommand(
    val provider: Provider,
    val returnTo: String,
)

data class BeginOidcLoginResult(
    val authorizeUrl: String,
)

class BeginOidcLoginUseCase(
    private val configSource: OidcProviderConfigSource,
    private val randomFactory: RandomFactory,
    private val idGenerator: IdGenerator,
    private val attempts: AuthAttemptRepository,
    private val clock: Clock,
    private val attemptTtl: Duration,
) {
    suspend fun execute(
        command: BeginOidcLoginCommand,
        linkToUserId: UserId? = null,
    ): BeginOidcLoginResult {
        val config = configSource.get(command.provider)
        val state = randomFactory.newState()
        val pkceVerifier = randomFactory.newPkceVerifier()
        val attempt =
            AuthAttempt(
                id = idGenerator.newAuthAttemptId(),
                state = state,
                pkceVerifier = pkceVerifier,
                provider = command.provider,
                returnTo = command.returnTo,
                linkToUserId = linkToUserId,
                expiresAt = clock.now().plus(attemptTtl),
            )
        attempts.create(attempt)
        return BeginOidcLoginResult(authorizeUrl = buildAuthorizeUrl(config, attempt))
    }

    private fun buildAuthorizeUrl(
        config: OidcProviderConfig,
        attempt: AuthAttempt,
    ): String {
        val params =
            listOf(
                "response_type" to "code",
                "client_id" to config.clientId,
                "redirect_uri" to config.redirectUri,
                // `openid` only — by design (ADR-0045): no `email`, no `profile`, no `name`.
                // Changing this requires superseding ADR-0045.
                "scope" to "openid",
                "state" to attempt.state.value,
                "code_challenge" to attempt.pkceVerifier.challenge(),
                "code_challenge_method" to "S256",
                "response_mode" to config.responseMode.toWire(),
            )
        val query =
            params.joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
            }
        return "${config.authorizeUrl}?$query"
    }
}
