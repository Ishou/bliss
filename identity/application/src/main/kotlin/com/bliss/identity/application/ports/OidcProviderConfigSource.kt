package com.bliss.identity.application.ports

import com.bliss.identity.domain.provider.Provider

enum class OidcResponseMode {
    QUERY,
    FORM_POST,
    ;

    fun toWire(): String =
        when (this) {
            QUERY -> "query"
            FORM_POST -> "form_post"
        }
}

/**
 * Per-provider OIDC configuration loaded from environment / k8s Secrets at
 * runtime. The application layer is agnostic to where the values come from;
 * the infrastructure layer ships the production `OidcProviderConfigSource`
 * binding in Phase 4 (wired from `application.conf` / env vars).
 *
 * `responseMode` is `QUERY` for Google (the OIDC default) and `FORM_POST`
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
    val responseMode: OidcResponseMode,
)

fun interface OidcProviderConfigSource {
    fun get(provider: Provider): OidcProviderConfig
}
