package com.bliss.identity.api.routes

import com.bliss.identity.application.ports.AuthAttemptRepository
import com.bliss.identity.application.usecases.CompleteOidcLoginCommand
import com.bliss.identity.application.usecases.CompleteOidcLoginUseCase
import com.bliss.identity.application.usecases.CompleteProviderLinkCommand
import com.bliss.identity.application.usecases.CompleteProviderLinkUseCase
import com.bliss.identity.domain.auth.State
import com.bliss.identity.domain.session.SessionId

// Routes an OIDC callback into either the login or linking flow based on the
// persisted AuthAttempt. Non-destructive peek via attempts.findByState(state);
// the consuming use case still performs its own read-and-delete.
class CallbackDispatcher(
    private val attempts: AuthAttemptRepository,
    private val completeOidcLogin: CompleteOidcLoginUseCase,
    private val completeProviderLink: CompleteProviderLinkUseCase,
) {
    sealed class Result {
        data class LoggedIn(
            val sessionId: SessionId,
            val returnTo: String,
        ) : Result()

        data class Linked(
            val returnTo: String,
        ) : Result()
    }

    suspend fun dispatch(
        state: String,
        code: String,
    ): Result {
        val attempt =
            try {
                attempts.findByState(State.of(state))
            } catch (_: IllegalArgumentException) {
                null
            }
        return if (attempt?.linkToUserId == null) {
            val r = completeOidcLogin.execute(CompleteOidcLoginCommand(state = state, code = code))
            Result.LoggedIn(sessionId = r.sessionId, returnTo = r.returnTo)
        } else {
            val r = completeProviderLink.execute(CompleteProviderLinkCommand(state = state, code = code))
            Result.Linked(returnTo = r.returnTo)
        }
    }
}
