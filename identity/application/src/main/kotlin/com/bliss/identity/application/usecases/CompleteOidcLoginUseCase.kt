package com.bliss.identity.application.usecases

import com.bliss.identity.application.ports.AuthAttemptRepository
import com.bliss.identity.application.ports.Clock
import com.bliss.identity.application.ports.IdGenerator
import com.bliss.identity.application.ports.OidcCodeExchanger
import com.bliss.identity.application.ports.OidcProviderConfigSource
import com.bliss.identity.application.ports.SessionRepository
import com.bliss.identity.application.ports.UserProviderRepository
import com.bliss.identity.application.ports.UserRepository
import com.bliss.identity.domain.auth.State
import com.bliss.identity.domain.oidc.OidcIdToken
import com.bliss.identity.domain.oidc.OidcProvider
import com.bliss.identity.domain.oidc.OidcVerifier
import com.bliss.identity.domain.provider.UserProvider
import com.bliss.identity.domain.session.Session
import com.bliss.identity.domain.session.SessionId
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId

private val DEFAULT_DISPLAY_NAME = DisplayName.of("Joueur")

data class CompleteOidcLoginCommand(
    val state: String,
    val code: String,
)

data class CompleteOidcLoginResult(
    val sessionId: SessionId,
    val userId: UserId,
    val returnTo: String,
)

class CompleteOidcLoginUseCase(
    private val attempts: AuthAttemptRepository,
    private val codeExchanger: OidcCodeExchanger,
    private val verifier: OidcVerifier,
    private val configSource: OidcProviderConfigSource,
    private val users: UserRepository,
    private val userProviders: UserProviderRepository,
    private val sessions: SessionRepository,
    private val idGenerator: IdGenerator,
    private val clock: Clock,
) {
    suspend fun execute(command: CompleteOidcLoginCommand): CompleteOidcLoginResult {
        val state =
            try {
                State.of(command.state)
            } catch (_: IllegalArgumentException) {
                throw CompleteOidcLoginError.UnknownState()
            }
        val attempt = attempts.findByState(state) ?: throw CompleteOidcLoginError.UnknownState()
        val now = clock.now()
        if (attempt.isExpired(now)) {
            attempts.deleteByState(state)
            throw CompleteOidcLoginError.StateExpired()
        }
        if (attempt.isLinkingMode) {
            // Leave the attempt intact — linking-aware use case will pick it up.
            throw CompleteOidcLoginError.LinkingNotSupportedHere()
        }
        // Consume the attempt before any side-effects, so a retried callback can't double-execute.
        attempts.deleteByState(state)

        val config = configSource.get(attempt.provider)
        val exchange =
            codeExchanger.exchange(
                provider = attempt.provider,
                code = command.code,
                pkceVerifier = attempt.pkceVerifier,
                redirectUri = config.redirectUri,
            )
        val verified: OidcIdToken =
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
        // TODO(phase-3): On Postgres, wrap `users.create` + `userProviders.link` + `sessions.create`
        // in a single transaction. The attempt has already been consumed, so a partial failure
        // strands the user (their attempt is gone, their record is half-written, and they can't
        // retry). The in-memory adapter masks this because it never throws.
        val existingLink = userProviders.findByProviderAndSubject(attempt.provider, verified.subject)
        val user: User =
            if (existingLink != null) {
                users.updateLastSeenAt(existingLink.userId, now)
                users.findById(existingLink.userId)
                    ?: throw CompleteOidcLoginError.OrphanedLink(existingLink.userId)
            } else {
                val created =
                    User(
                        id = idGenerator.newUserId(),
                        displayName = DEFAULT_DISPLAY_NAME,
                        createdAt = now,
                        lastSeenAt = now,
                    )
                users.create(created)
                userProviders.link(
                    UserProvider(
                        userId = created.id,
                        provider = attempt.provider,
                        subject = verified.subject,
                        emailAtLink = null,
                        linkedAt = now,
                    ),
                )
                created
            }
        val session =
            Session(
                id = idGenerator.newSessionId(),
                userId = user.id,
                createdAt = now,
                lastSeenAt = now,
                revokedAt = null,
            )
        sessions.create(session)
        return CompleteOidcLoginResult(
            sessionId = session.id,
            userId = user.id,
            returnTo = attempt.returnTo,
        )
    }
}
