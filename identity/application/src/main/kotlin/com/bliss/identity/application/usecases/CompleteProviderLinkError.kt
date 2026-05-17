package com.bliss.identity.application.usecases

import com.bliss.identity.domain.user.UserId

sealed class CompleteProviderLinkError(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class UnknownState : CompleteProviderLinkError("Unknown or expired state.")

    class StateExpired : CompleteProviderLinkError("State expired before callback.")

    class NotLinkingMode : CompleteProviderLinkError("This attempt was opened in sign-in mode; use CompleteOidcLoginUseCase.")

    class LinkConflict(
        val owningUserId: UserId,
    ) : CompleteProviderLinkError("Provider already linked to a different user (id=$owningUserId).")

    class ExchangeRejected(
        cause: Throwable,
    ) : CompleteProviderLinkError("Token endpoint rejected the request: ${cause.message}", cause)
}
