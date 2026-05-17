package com.bliss.identity.application.usecases

import com.bliss.identity.domain.user.UserId

sealed class CompleteOidcLoginError(
    message: String,
) : RuntimeException(message) {
    class UnknownState : CompleteOidcLoginError("Unknown state.")

    class StateExpired : CompleteOidcLoginError("State expired before callback.")

    class LinkingNotSupportedHere :
        CompleteOidcLoginError(
            "This attempt was opened in linking mode; use CompleteProviderLinkUseCase.",
        )

    class OrphanedLink(
        val userId: UserId,
    ) : CompleteOidcLoginError(
            "UserProvider references a non-existent user (id=$userId).",
        )
}
