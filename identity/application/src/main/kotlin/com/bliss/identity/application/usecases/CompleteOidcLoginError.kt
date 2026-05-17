package com.bliss.identity.application.usecases

sealed class CompleteOidcLoginError(
    message: String,
) : RuntimeException(message) {
    class UnknownState : CompleteOidcLoginError("Unknown or expired state.")

    class StateExpired : CompleteOidcLoginError("State expired before callback.")

    class LinkingNotSupportedHere :
        CompleteOidcLoginError(
            "This attempt was opened in linking mode; use CompleteProviderLinkUseCase.",
        )
}
