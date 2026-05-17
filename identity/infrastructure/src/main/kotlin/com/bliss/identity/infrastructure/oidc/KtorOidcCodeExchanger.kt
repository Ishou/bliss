package com.bliss.identity.infrastructure.oidc

import com.bliss.identity.application.ports.ClientAssertionSigner
import com.bliss.identity.application.ports.ClientAuth
import com.bliss.identity.application.ports.Clock
import com.bliss.identity.application.ports.OidcCodeExchanger
import com.bliss.identity.application.ports.OidcExchangeError
import com.bliss.identity.application.ports.OidcExchangeResult
import com.bliss.identity.application.ports.OidcProviderConfigSource
import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.provider.Provider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/** Exchanges an OAuth 2.0 authorization code for an OIDC ID token via Ktor's HTTP client. */
class KtorOidcCodeExchanger(
    private val configSource: OidcProviderConfigSource,
    engine: HttpClientEngine,
    private val clock: Clock = Clock { Instant.now() },
) : OidcCodeExchanger {
    private val client =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    },
                )
            }
        }

    override suspend fun exchange(
        provider: Provider,
        code: String,
        pkceVerifier: PkceVerifier,
        redirectUri: String,
    ): OidcExchangeResult {
        val config = configSource.get(provider)
        val now = clock.now()

        val params =
            parameters {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
                append("client_id", config.clientId)
                append("code_verifier", pkceVerifier.value)
                when (val auth = config.clientAuth) {
                    is ClientAuth.Secret -> {
                        append("client_secret", auth.clientSecret)
                    }
                    is ClientAuth.AppleClientAssertion -> {
                        val signer: ClientAssertionSigner =
                            AppleClientAssertionSigner(
                                teamId = auth.teamId,
                                keyId = auth.keyId,
                                privateKeyPem = auth.privateKeyPem,
                                clientId = config.clientId,
                                tokenUrl = config.tokenUrl,
                            )
                        append(
                            "client_assertion_type",
                            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
                        )
                        append("client_assertion", signer.sign(now))
                    }
                }
            }

        val httpResponse = client.submitForm(config.tokenUrl, params)
        if (httpResponse.status.value !in 200..299) {
            throw OidcExchangeError.TokenEndpointRejected(
                provider = provider,
                httpStatus = httpResponse.status.value,
                cause = IllegalStateException("Token endpoint returned HTTP ${httpResponse.status.value}"),
            )
        }
        val response: TokenResponse = httpResponse.body()
        return OidcExchangeResult(idToken = response.idToken)
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("id_token") val idToken: String,
    )
}
