package com.bliss.game.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.game.api.module
import io.ktor.client.request.headers
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
    fun `preflight allows W3C Trace Context headers attached by OTel SDK`() =
        testApplication {
            application { module() }

            // Same regression pattern as grid-api: PR-F.2's FetchInstrumentation
            // attaches `traceparent` / `tracestate` to wordsparrow.io fetches.
            // Both must be in `allowHeader` or the preflight returns no
            // Allow-Headers and the browser blocks the request — every POST
            // /v1/lobbies / GET lobby fails as "Failed to fetch".
            val response =
                client.options("/v1/lobbies") {
                    headers {
                        append(HttpHeaders.Origin, "https://wordsparrow.io")
                        append(HttpHeaders.AccessControlRequestMethod, "POST")
                        append(
                            HttpHeaders.AccessControlRequestHeaders,
                            "Content-Type, X-Request-Id, traceparent, tracestate, baggage",
                        )
                    }
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin])
                .isEqualTo("https://wordsparrow.io")
            val allowHeaders =
                response.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().lowercase()
            assertThat(allowHeaders).contains("traceparent")
            assertThat(allowHeaders).contains("tracestate")
            assertThat(allowHeaders).contains("baggage")
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
