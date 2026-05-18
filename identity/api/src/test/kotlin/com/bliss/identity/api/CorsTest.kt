package com.bliss.identity.api

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.bliss.identity.api.config.AppleClientConfig
import com.bliss.identity.api.config.GoogleClientConfig
import com.bliss.identity.api.config.IdentityApiConfig
import io.ktor.client.request.headers
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

class CorsTest {
    private val testConfig =
        IdentityApiConfig(
            port = 0,
            publicHost = "auth.wordsparrow.example",
            google = GoogleClientConfig("g-client", "g-secret"),
            apple =
                AppleClientConfig(
                    "a-svc",
                    "a-team",
                    "a-key",
                    "-----BEGIN PRIVATE KEY-----\n-----END PRIVATE KEY-----",
                ),
            allowedReturnOrigins = listOf("https://wordsparrow.example"),
        )

    @Test
    fun `preflight from wordsparrow_io returns credentials-allowed CORS headers`() =
        testApplication {
            application { module(Wiring.forTesting(), testConfig) }
            val response =
                client.options("/v1/auth/whoami") {
                    headers {
                        append(HttpHeaders.Origin, "https://wordsparrow.io")
                        append(HttpHeaders.AccessControlRequestMethod, "GET")
                    }
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin] ?: "")
                .isEqualTo("https://wordsparrow.io")
            assertThat(response.headers[HttpHeaders.AccessControlAllowCredentials] ?: "")
                .isEqualTo("true")
        }

    @Test
    fun `preflight from disallowed origin omits CORS headers`() =
        testApplication {
            application { module(Wiring.forTesting(), testConfig) }
            val response =
                client.options("/v1/auth/whoami") {
                    headers {
                        append(HttpHeaders.Origin, "https://evil.example")
                        append(HttpHeaders.AccessControlRequestMethod, "GET")
                    }
                }
            // Ktor's CORS plugin returns 403 for disallowed origins on preflight.
            assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin]).isEqualTo(null)
        }

    @Test
    fun `preflight allows PATCH on users-me`() =
        testApplication {
            application { module(Wiring.forTesting(), testConfig) }
            val response =
                client.options("/v1/users/me") {
                    headers {
                        append(HttpHeaders.Origin, "https://wordsparrow.io")
                        append(HttpHeaders.AccessControlRequestMethod, "PATCH")
                        append(HttpHeaders.AccessControlRequestHeaders, "Content-Type")
                    }
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers[HttpHeaders.AccessControlAllowMethods] ?: "").contains("PATCH")
            assertThat(response.headers[HttpHeaders.AccessControlAllowHeaders] ?: "").contains("Content-Type")
        }
}
