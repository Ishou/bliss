package com.bliss.survey.api

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.bliss.survey.api.config.SurveyApiConfig
import io.ktor.client.request.headers
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

class CorsTest {
    private val testConfig =
        SurveyApiConfig(
            port = 0,
            jdbcUrl = "jdbc:postgresql://localhost/survey",
            dbUser = "test",
            dbPassword = "test",
            identityBaseUrl = "https://auth.example",
            allowedOrigins = listOf("https://wordsparrow.io"),
            natsUrl = "nats://localhost:4222",
        )

    @Test
    fun `preflight from wordsparrow_io returns credentials-allowed CORS headers`() =
        testApplication {
            application { installSurveyCors(testConfig) }
            val response =
                client.options("/v1/items/next") {
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
            application { installSurveyCors(testConfig) }
            val response =
                client.options("/v1/items/next") {
                    headers {
                        append(HttpHeaders.Origin, "https://evil.example")
                        append(HttpHeaders.AccessControlRequestMethod, "GET")
                    }
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin]).isEqualTo(null)
        }

    @Test
    fun `preflight allows PATCH on me-preferences`() =
        testApplication {
            application { installSurveyCors(testConfig) }
            val response =
                client.options("/v1/me/preferences") {
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

    // Regression test for the 5th CORS incident (2026-05-26): the survey-api shipped
    // with `allowHeader(ContentType)` + `allowHeader(Cookie)` only, so the OTel browser
    // SDK's traceparent/tracestate triggered a 403 preflight that surfaced as a raw
    // "Failed to fetch" on /sondage. ADR-0048 mandates the wildcard predicate for any
    // credentialed-CORS bounded context exactly to prevent this.
    @Test
    fun `preflight allows arbitrary frontend headers via predicate wildcard`() =
        testApplication {
            application { installSurveyCors(testConfig) }
            val response =
                client.options("/v1/items/next") {
                    headers {
                        append(HttpHeaders.Origin, "https://wordsparrow.io")
                        append(HttpHeaders.AccessControlRequestMethod, "GET")
                        append(
                            HttpHeaders.AccessControlRequestHeaders,
                            "x-request-id,traceparent,tracestate",
                        )
                    }
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val allowed = response.headers[HttpHeaders.AccessControlAllowHeaders] ?: ""
            assertThat(allowed).contains("x-request-id")
            assertThat(allowed).contains("traceparent")
            assertThat(allowed).contains("tracestate")
        }
}
