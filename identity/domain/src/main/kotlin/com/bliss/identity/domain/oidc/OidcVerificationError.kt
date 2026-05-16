package com.bliss.identity.domain.oidc

sealed class OidcVerificationError(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class InvalidSignature : OidcVerificationError("ID token signature verification failed.")

    class IssuerMismatch(
        val expected: String,
        val got: String,
    ) : OidcVerificationError("ID token issuer mismatch: expected '$expected', got '$got'.")

    class AudienceMismatch(
        val expected: String,
        val got: String,
    ) : OidcVerificationError("ID token audience mismatch: expected '$expected', got '$got'.")

    class TokenExpired(
        val expiresAt: java.time.Instant,
    ) : OidcVerificationError("ID token expired at $expiresAt.")

    class MissingSubject : OidcVerificationError("ID token has no 'sub' claim.")

    class JwksUnavailable(
        cause: Throwable,
    ) : OidcVerificationError("Could not fetch JWKS: ${cause.message}", cause)

    class Malformed(
        cause: Throwable,
    ) : OidcVerificationError("Could not parse ID token: ${cause.message}", cause)
}
