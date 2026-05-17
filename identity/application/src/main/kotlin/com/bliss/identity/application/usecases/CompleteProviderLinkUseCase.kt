package com.bliss.identity.application.usecases

import com.bliss.identity.application.ports.AuthAttemptRepository
import com.bliss.identity.application.ports.Clock
import com.bliss.identity.application.ports.OidcCodeExchanger
import com.bliss.identity.application.ports.OidcExchangeError
import com.bliss.identity.application.ports.OidcProviderConfigSource
import com.bliss.identity.application.ports.UserProviderRepository
import com.bliss.identity.domain.auth.State
import com.bliss.identity.domain.oidc.OidcProvider
import com.bliss.identity.domain.oidc.OidcVerifier
import com.bliss.identity.domain.provider.UserProvider
import com.bliss.identity.domain.user.UserId
import kotlin.coroutines.cancellation.CancellationException

data class CompleteProviderLinkCommand(
    val state: String,
    val code: String,
)

data class CompleteProviderLinkResult(
    val userId: UserId,
    val returnTo: String,
)

class CompleteProviderLinkUseCase(
    private val attempts: AuthAttemptRepository,
    private val codeExchanger: OidcCodeExchanger,
    private val verifier: OidcVerifier,
    private val configSource: OidcProviderConfigSource,
    private val userProviders: UserProviderRepository,
    private val clock: Clock,
) {
    suspend fun execute(command: CompleteProviderLinkCommand): CompleteProviderLinkResult {
        val state =
            try {
                State.of(command.state)
            } catch (_: IllegalArgumentException) {
                throw CompleteProviderLinkError.UnknownState()
            }
        val attempt = attempts.findByState(state) ?: throw CompleteProviderLinkError.UnknownState()
        val now = clock.now()
        if (attempt.isExpired(now)) {
            attempts.deleteByState(state)
            throw CompleteProviderLinkError.StateExpired()
        }
        if (!attempt.isLinkingMode) {
            // Leave the attempt intact - sign-in-aware use case will pick it up.
            throw CompleteProviderLinkError.NotLinkingMode()
        }
        val linkToUserId = attempt.linkToUserId!!
        // Consume the attempt before any side-effects, so a retried callback can't double-execute.
        attempts.deleteByState(state)

        val config = configSource.get(attempt.provider)
        val exchange =
            try {
                codeExchanger.exchange(attempt.provider, command.code, attempt.pkceVerifier, config.redirectUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OidcExchangeError.TokenEndpointRejected) {
                throw CompleteProviderLinkError.ExchangeRejected(e)
            }
        val verified =
            verifier.verify(
                rawIdToken = exchange.idToken,
                provider =
                    OidcProvider(
                        provider = attempt.provider,
                        issuer = config.issuer,
                        audience = config.audience,
                        jwksUri = config.jwksUri,
                    ),
            )
        // Nonce validation deferred: ADR-0044 §"ID-token replay" promises nonce is tied to the
        // attempt and rejected on mismatch. Full implementation requires a `nonce` field on
        // AuthAttempt, generated in BeginOidcLoginUseCase and included in the authorize URL, then
        // validated as `verified.nonce == attempt.nonce` here (with NonceMismatch error). PKCE
        // mitigates authorization-code replay in the interim; ADR-0044 will be amended when
        // this follow-up workstream lands.
        // TODO(phase-nonce): add AuthAttempt.nonce, BeginOidcLoginUseCase nonce param, validate here.
        // TODO(phase-3): On Postgres, wrap `attempts.deleteByState` + `userProviders.link`
        // in a single transaction. The attempt has already been consumed, so a partial failure
        // strands the user (their attempt is gone, their link is not written, and they can't
        // retry). The in-memory adapter masks this because it never throws.
        val existingLink = userProviders.findByProviderAndSubject(attempt.provider, verified.subject)
        if (existingLink != null) {
            if (existingLink.userId != linkToUserId) {
                throw CompleteProviderLinkError.LinkConflict(existingLink.userId)
            }
            // Same user already linked to this provider - idempotent no-op.
            return CompleteProviderLinkResult(userId = linkToUserId, returnTo = attempt.returnTo)
        }
        userProviders.link(
            UserProvider(
                userId = linkToUserId,
                provider = attempt.provider,
                subject = verified.subject,
                emailAtLink = null,
                linkedAt = now,
            ),
        )
        return CompleteProviderLinkResult(userId = linkToUserId, returnTo = attempt.returnTo)
    }
}
