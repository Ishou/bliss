package com.bliss.game.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.game.api.module
import io.ktor.client.request.headers
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

/**
 * Wire-path tests for game-api CORS — mirrors grid-api's `CorsTest`. The
 * frontend at `https://wordsparrow.io` calls `https://game.wordsparrow.io`
 * cross-origin, so every request triggers a preflight; these tests assert
 * the allowlist matches reality (prod apex + www + Vite dev), the W3C
 * Trace Context headers attached by the OTel browser SDK pass, and that
 * unauthorised origins are rejected.
 *
 * Originated as part of the fix for the "preflight 403 → POST hint /
 * validate broken" regression caused by PR-F.2 (#298) shipping
 * `traceparent` / `tracestate` on outbound fetches without updating the
 * server allowlist. The recurring CORS tax (memory) explicitly warned
 * against this; the test mirrors grid-api's so a regression here fails CI
 * before prod.
 */
class CorsTest {
    private val anyLobbyId = "ABC12345"

    @Test
    fun `preflight from prod origin is allowed`() =
        testApplication {
            application { module() }

            val response =
                client.options("/v1/lobbies/$anyLobbyId") {
                    headers {
                        append(HttpHeaders.Origin, "https://wordsparrow.io")
                        append(HttpHeaders.AccessControlRequestMethod, "GET")
                    }
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin])
                .isEqualTo("https://wordsparrow.io")
            assertThat(response.headers[HttpHeaders.AccessControlAllowMethods]).isNotNull()
            assertThat(response.headers[HttpHeaders.AccessControlMaxAge])
                .isEqualTo("86400")
        }

    @Test
    fun `preflight from www prod origin is allowed`() =
        testApplication {
            application { module() }

            val response =
                client.options("/v1/lobbies/$anyLobbyId") {
                    headers {
                        append(HttpHeaders.Origin, "https://www.wordsparrow.io")
                        append(HttpHeaders.AccessControlRequestMethod, "GET")
                    }
                }

            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin])
                .isEqualTo("https://www.wordsparrow.io")
        }

    @Test
    fun `preflight from local Vite dev origin is allowed`() =
        testApplication {
            application { module() }

            val response =
                client.options("/v1/lobbies/$anyLobbyId") {
                    headers {
                        append(HttpHeaders.Origin, "http://localhost:5173")
                        append(HttpHeaders.AccessControlRequestMethod, "POST")
                    }
                }

            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin])
                .isEqualTo("http://localhost:5173")
        }

    @Test
    fun `preflight allows arbitrary headers (ADR-0034 wildcard)`() =
        testApplication {
            application { module() }

            // Mirrors grid-api/CorsTest's wildcard assertion. The
            // historically-incident-prone keys plus an unfamiliar one
            // all come back in Access-Control-Allow-Headers — exactly
            // what ADR-0034 buys.
            val response =
                client.options("/v1/lobbies") {
                    headers {
                        append(HttpHeaders.Origin, "https://wordsparrow.io")
                        append(HttpHeaders.AccessControlRequestMethod, "POST")
                        append(
                            HttpHeaders.AccessControlRequestHeaders,
                            "Content-Type, X-Request-Id, traceparent, tracestate, baggage, X-Foo-Future",
                        )
                    }
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin])
                .isEqualTo("https://wordsparrow.io")
            val allowHeaders =
                response.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().lowercase()
            assertThat(allowHeaders).contains("x-request-id")
            assertThat(allowHeaders).contains("traceparent")
            assertThat(allowHeaders).contains("tracestate")
            assertThat(allowHeaders).contains("baggage")
            assertThat(allowHeaders).contains("x-foo-future")
        }

    @Test
    fun `cross-origin POST with application-json from allowed origin is not 403'd by CORS`() =
        testApplication {
            application { module() }

            // Reproduces the prod regression where browsers POSTing JSON to
            // `/v1/lobbies` from `https://www.wordsparrow.io` were rejected by
            // Ktor's CORS plugin with 403 (no `Access-Control-Allow-Origin`),
            // even though the preflight succeeded. Root cause: by default the
            // plugin treats `application/json` as a non-simple content type
            // and blocks the actual request unless `allowNonSimpleContentTypes`
            // is enabled. Symptom in the browser console:
            // "blocked by CORS policy: No 'Access-Control-Allow-Origin' header
            // is present on the requested resource."
            //
            // The body is intentionally malformed — what we are asserting is
            // that the request reaches the route and the response carries the
            // ACAO header. A 400/500 from validation is fine; a 403 with no
            // ACAO is the bug.
            val response =
                client.post("/v1/lobbies") {
                    headers { append(HttpHeaders.Origin, "https://www.wordsparrow.io") }
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }

            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin])
                .isEqualTo("https://www.wordsparrow.io")
            assertThat(response.status).isNotEqualTo(HttpStatusCode.Forbidden)
            // Sanity: the route — not the CORS plugin — answered. An empty
            // body from a CORS rejection would defeat the previous assertion
            // alone in some Ktor versions.
            assertThat(response.bodyAsText()).isNotEmpty()
        }

    @Test
    fun `disallowed origin gets no Allow-Origin header`() =
        testApplication {
            application { module() }

            val response =
                client.options("/v1/lobbies/$anyLobbyId") {
                    headers {
                        append(HttpHeaders.Origin, "https://evil.example")
                        append(HttpHeaders.AccessControlRequestMethod, "GET")
                    }
                }

            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin]).isNull()
        }
}
