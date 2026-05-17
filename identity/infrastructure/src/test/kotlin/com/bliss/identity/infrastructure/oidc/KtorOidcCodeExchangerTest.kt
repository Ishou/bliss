package com.bliss.identity.infrastructure.oidc

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.bliss.identity.application.ports.ClientAuth
import com.bliss.identity.application.ports.OidcExchangeError
import com.bliss.identity.application.ports.OidcProviderConfig
import com.bliss.identity.application.ports.OidcResponseMode
import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.provider.Provider
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class KtorOidcCodeExchangerTest {
    private val idTokenJson =
        """{"access_token":"at","token_type":"bearer","id_token":"raw-id-token"}"""

    private fun mockEngine(
        captured: MutableList<String>,
        responseBody: String = idTokenJson,
    ): MockEngine =
        MockEngine { request ->
            captured.add(request.body.toByteArray().toString(Charsets.UTF_8))
            respond(
                content = responseBody,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }

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
            clientAuth = ClientAuth.Secret("google-secret"),
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
            clientAuth =
                ClientAuth.AppleClientAssertion(
                    teamId = "TEAM123",
                    keyId = "KEY456",
                    privateKeyPem = generateTestPem(),
                ),
        )

    private fun configSource(config: OidcProviderConfig) = StaticOidcProviderConfigSource(mapOf(config.provider to config))

    @Test
    fun `Google exchange posts client_secret_post form fields`() =
        runTest {
            val captured = mutableListOf<String>()
            val exchanger =
                KtorOidcCodeExchanger(
                    configSource = configSource(googleConfig),
                    engine = mockEngine(captured),
                )
            val result =
                exchanger.exchange(
                    provider = Provider.GOOGLE,
                    code = "auth-code-1",
                    pkceVerifier = PkceVerifier.of("verifier-abcdefghijklmnopqrstuvwxyz0123456789AB"),
                    redirectUri = "https://auth.wordsparrow.io/v1/auth/google/callback",
                )
            assertThat(result.idToken).isEqualTo<String>("raw-id-token")
            val body = captured.first()
            assertThat(body).contains("grant_type=authorization_code")
            assertThat(body).contains("code=auth-code-1")
            assertThat(body).contains("client_id=google-client")
            assertThat(body).contains("client_secret=google-secret")
            assertThat(body).contains("code_verifier=verifier-abcdefghijklmnopqrstuvwxyz0123456789AB")
        }

    @Test
    fun `Apple exchange posts client_assertion instead of client_secret`() =
        runTest {
            val captured = mutableListOf<String>()
            val exchanger =
                KtorOidcCodeExchanger(
                    configSource = configSource(appleConfig),
                    engine = mockEngine(captured),
                )
            exchanger.exchange(
                provider = Provider.APPLE,
                code = "apple-code-1",
                pkceVerifier = PkceVerifier.of("verifier-abcdefghijklmnopqrstuvwxyz0123456789AB"),
                redirectUri = "https://auth.wordsparrow.io/v1/auth/apple/callback",
            )
            val body = captured.first()
            assertThat(body).contains("grant_type=authorization_code")
            assertThat(body).contains("client_id=apple-service-id")
            assertThat(body).contains("client_assertion_type=urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer")
            assertThat(body).contains("client_assertion=")
        }

    @Test
    fun `Apple exchange does not post client_secret`() =
        runTest {
            val captured = mutableListOf<String>()
            val exchanger =
                KtorOidcCodeExchanger(
                    configSource = configSource(appleConfig),
                    engine = mockEngine(captured),
                )
            exchanger.exchange(
                provider = Provider.APPLE,
                code = "apple-code-2",
                pkceVerifier = PkceVerifier.of("verifier-abcdefghijklmnopqrstuvwxyz0123456789AB"),
                redirectUri = "https://auth.wordsparrow.io/v1/auth/apple/callback",
            )
            val body = captured.first()
            // client_secret must NOT appear in an Apple assertion flow
            assertThat(body.contains("client_secret")).isEqualTo(false)
        }

    @Test
    fun `token endpoint 4xx is wrapped as OidcExchangeError_TokenEndpointRejected`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = """{"error":"invalid_grant"}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
            val exchanger =
                KtorOidcCodeExchanger(
                    configSource = configSource(googleConfig),
                    engine = engine,
                )
            assertFailure {
                exchanger.exchange(
                    provider = Provider.GOOGLE,
                    code = "expired-code",
                    pkceVerifier = PkceVerifier.of("verifier-abcdefghijklmnopqrstuvwxyz0123456789AB"),
                    redirectUri = "https://auth.wordsparrow.io/v1/auth/google/callback",
                )
            }.isInstanceOf(OidcExchangeError.TokenEndpointRejected::class)
        }
}

private fun generateTestPem(): String {
    val key =
        com.nimbusds.jose.jwk.gen
            .ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256)
            .keyID("KEY456")
            .generate()
    val privateBytes = key.toECPrivateKey().encoded
    val base64 =
        java.util.Base64
            .getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(privateBytes)
    return "-----BEGIN PRIVATE KEY-----\n$base64\n-----END PRIVATE KEY-----\n"
}
