package com.bliss.identity.application.ports

import com.bliss.identity.domain.provider.Provider

/**
 * Per-provider OIDC configuration loaded from environment / k8s Secrets at
 * runtime. The application layer is agnostic to where the values come from;
 * the infrastructure layer ships the production `OidcProviderConfigSource`
 * binding in Phase 4 (wired from `application.conf` / env vars).
 *
 * `responseMode` is `query` for Google (the OIDC default) and `form_post`
 * for Apple — Apple posts the callback with `application/x-www-form-urlencoded`.
 */
data class OidcProviderConfig(
    val provider: Provider,
    val issuer: String,
    val audience: String,
    val clientId: String,
    val authorizeUrl: String,
    val tokenUrl: String,
    val jwksUri: String,
    val redirectUri: String,
    val responseMode: String,
)

fun interface OidcProviderConfigSource {
    fun get(provider: Provider): OidcProviderConfig
}
