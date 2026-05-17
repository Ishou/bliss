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

/** How the client authenticates at the token endpoint. */
sealed interface ClientAuth {
    /** Google-style: static client secret posted with the token request. */
    class Secret(
        val clientSecret: String,
    ) : ClientAuth {
        override fun toString() = "Secret(clientSecret=***)"
    }

    /** Apple-style: ES256-signed JWT assertion (`private_key_jwt`). */
    class AppleClientAssertion(
        val teamId: String,
        val keyId: String,
        val privateKeyPem: String,
    ) : ClientAuth {
        override fun toString() = "AppleClientAssertion(teamId=$teamId, keyId=$keyId, privateKeyPem=***)"
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
    val clientAuth: ClientAuth,
)

fun interface OidcProviderConfigSource {
    fun get(provider: Provider): OidcProviderConfig
}
