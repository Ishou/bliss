package com.bliss.identity.infrastructure.oidc

import com.bliss.identity.application.ports.OidcProviderConfig
import com.bliss.identity.application.ports.OidcProviderConfigSource
import com.bliss.identity.domain.provider.Provider

class StaticOidcProviderConfigSource(
    private val configs: Map<Provider, OidcProviderConfig>,
) : OidcProviderConfigSource {
    override fun get(provider: Provider): OidcProviderConfig =
        configs[provider]
            ?: error("OIDC provider $provider is not configured.")
}
