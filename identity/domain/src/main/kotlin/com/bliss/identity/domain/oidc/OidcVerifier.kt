package com.bliss.identity.domain.oidc

/**
 * Port. Verifies an OIDC ID token returned from a provider's token endpoint
 * against the expected issuer, audience, and signature (via the provider's
 * JWKS).
 *
 * Implementation lives in `identity/infrastructure` and uses a JOSE library.
 * Domain stays SDK-free.
 */
fun interface OidcVerifier {
    /**
     * @throws OidcVerificationError on any verification failure.
     */
    suspend fun verify(
        rawIdToken: String,
        provider: OidcProvider,
    ): OidcIdToken
}
