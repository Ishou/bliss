package com.bliss.identity.infrastructure.oidc

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.bliss.identity.application.ports.ClientAuth
import com.bliss.identity.application.ports.OidcProviderConfig
import com.bliss.identity.application.ports.OidcResponseMode
import com.bliss.identity.domain.provider.Provider
import org.junit.jupiter.api.Test

class StaticOidcProviderConfigSourceTest {
    private val googleConfig =
        OidcProviderConfig(
            provider = Provider.GOOGLE,
            issuer = "https://accounts.google.com",
            audience = "google-client",
            clientId = "google-client",
            authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenUrl = "https://oauth2.googleapis.com/token",
            jwksUri = "https://www.googleapis.com/oauth2/v3/certs",
            redirectUri = "https://auth.wordsparrow.io/v1/auth/google/callback",
            responseMode = OidcResponseMode.QUERY,
            clientAuth = ClientAuth.Secret("test-secret"),
        )

    private val appleConfig =
        OidcProviderConfig(
            provider = Provider.APPLE,
            issuer = "https://appleid.apple.com",
            audience = "apple-service-id",
            clientId = "apple-service-id",
            authorizeUrl = "https://appleid.apple.com/auth/authorize",
            tokenUrl = "https://appleid.apple.com/auth/token",
            jwksUri = "https://appleid.apple.com/auth/keys",
            redirectUri = "https://auth.wordsparrow.io/v1/auth/apple/callback",
            responseMode = OidcResponseMode.FORM_POST,
            clientAuth = ClientAuth.Secret("test-secret"),
        )

    @Test
    fun `get returns the configured value`() {
        val source = StaticOidcProviderConfigSource(mapOf(Provider.GOOGLE to googleConfig, Provider.APPLE to appleConfig))
        assertThat(source.get(Provider.GOOGLE)).isEqualTo(googleConfig)
        assertThat(source.get(Provider.APPLE)).isEqualTo(appleConfig)
    }

    @Test
    fun `get throws when the provider is not configured`() {
        val source = StaticOidcProviderConfigSource(mapOf(Provider.GOOGLE to googleConfig))
        assertFailure { source.get(Provider.APPLE) }.isInstanceOf(IllegalStateException::class)
    }
}
