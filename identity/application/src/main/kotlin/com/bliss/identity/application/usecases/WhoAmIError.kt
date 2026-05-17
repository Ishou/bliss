package com.bliss.identity.application.usecases

sealed class WhoAmIError(
    message: String,
) : RuntimeException(message) {
    class SessionNotFound : WhoAmIError("Session does not exist.")

    class SessionRevoked : WhoAmIError("Session was revoked.")

    class SessionExpired : WhoAmIError("Session exceeded its max-age.")

    class OrphanedSession : WhoAmIError("Session references a missing user (likely mid-delete).")
}
