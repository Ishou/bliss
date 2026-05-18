package com.bliss.identity.api.config

import com.bliss.identity.application.ports.ClientAuth
import java.time.Duration

data class GoogleClientConfig(
    val clientId: String,
    val clientSecret: String,
)

data class AppleClientConfig(
    val serviceId: String,
    val teamId: String,
    val keyId: String,
    val privateKeyPem: String,
)

// Public host for redirect URIs (e.g. `auth.wordsparrow.io`); see ADR-0044 amendment.
data class IdentityApiConfig(
    val port: Int = 7779,
    val publicHost: String,
    val sessionMaxAge: Duration = Duration.ofDays(7),
    val attemptTtl: Duration = Duration.ofMinutes(5),
    val google: GoogleClientConfig,
    val apple: AppleClientConfig,
    val allowedReturnOrigins: List<String>,
) {
    val googleAuth: ClientAuth.Secret get() = ClientAuth.Secret(google.clientSecret)
    val appleAuth: ClientAuth.AppleClientAssertion
        get() =
            ClientAuth.AppleClientAssertion(
                teamId = apple.teamId,
                keyId = apple.keyId,
                privateKeyPem = apple.privateKeyPem,
            )

    companion object {
        fun fromEnv(): IdentityApiConfig =
            IdentityApiConfig(
                publicHost = requireEnv("COOKIE_DOMAIN").trimStart('.'),
                google =
                    GoogleClientConfig(
                        clientId = requireEnv("GOOGLE_OAUTH_CLIENT_ID"),
                        clientSecret = requireEnv("GOOGLE_OAUTH_CLIENT_SECRET"),
                    ),
                apple =
                    AppleClientConfig(
                        serviceId = requireEnv("APPLE_SERVICE_ID"),
                        teamId = requireEnv("APPLE_TEAM_ID"),
                        keyId = requireEnv("APPLE_KEY_ID"),
                        privateKeyPem = requireEnv("APPLE_PRIVATE_KEY_PEM"),
                    ),
                allowedReturnOrigins = requireEnv("ALLOWED_RETURN_ORIGINS").split(",").map { it.trim() },
            )

        private fun requireEnv(name: String): String = System.getenv(name) ?: error("Required env var $name is unset.")
    }
}
