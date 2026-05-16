package com.bliss.identity.application.ports

import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.provider.Provider

/**
 * Exchanges an OAuth 2.0 authorization code for an OIDC ID token at the
 * provider's token endpoint. Real adapter lands in Phase 3 (Ktor HTTP client).
 *
 * The returned ID token is a raw JWT string; signature/claims verification
 * is performed separately by `com.bliss.identity.domain.oidc.OidcVerifier`.
 */
data class OidcExchangeResult(val idToken: String)

fun interface OidcCodeExchanger {
    suspend fun exchange(
        provider: Provider,
        code: String,
        pkceVerifier: PkceVerifier,
        redirectUri: String,
    ): OidcExchangeResult
}
