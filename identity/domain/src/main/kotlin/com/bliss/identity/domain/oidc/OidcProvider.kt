package com.bliss.identity.domain.oidc

import com.bliss.identity.domain.provider.Provider

/**
 * Per-provider OIDC configuration that the verifier needs to enforce claims.
 * Concrete values come from `identity/application` via the use case.
 */
data class OidcProvider(
    val provider: Provider,
    val issuer: String,
    val audience: String,
    val jwksUri: String,
)
