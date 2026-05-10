package com.bliss.grid.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.grid.api.module
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

/**
 * Wire-path tests for CORS. The frontend at `https://wordsparrow.io` is
 * served from a separate origin from `https://api.wordsparrow.io`, so the
 * browser executes a CORS preflight on every cross-origin request. These
 * tests assert the allowlist matches the production reality (prod apex +
 * www + local Vite dev) and that nothing else slips through — preview
 * deploys are deliberately excluded because they mock the API via MSW
 * (ADR-0007 §5).
 */
class CorsTest {
    private val validId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"

    @Test
    fun `preflight from prod origin is allowed and returns CORS headers`() =
        testApplication {
            application { module() }

            val response =
                client.options("/v1/puzzles/$validId") {
                    headers {
                        append(HttpHeaders.Origin, "https://wordsparrow.io")
                        append(HttpHeaders.AccessControlRequestMethod, "GET")
                    }
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin])
                .isEqualTo("https://wordsparrow.io")
            // Ktor's CORS plugin only emits non-simple methods in
            // Allow-Methods (GET is a CORS-simple method and is implicit).
            // The header must still appear so the preflight is well-formed.
            assertThat(response.headers[HttpHeaders.AccessControlAllowMethods])
                .isNotNull()
            assertThat(response.headers[HttpHeaders.AccessControlAllowMethods]!!)
                .contains("OPTIONS")
            // 24h cache, per Module.kt config.
            assertThat(response.headers[HttpHeaders.AccessControlMaxAge])
                .isEqualTo("86400")
        }

    @Test
    fun `actual GET from prod origin echoes Allow-Origin`() =
        testApplication {
            application { module() }

            val response =
                client.get("/v1/puzzles/$validId") {
                    headers { append(HttpHeaders.Origin, "https://wordsparrow.io") }
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin])
                .isEqualTo("https://wordsparrow.io")
        }

    @Test
    fun `disallowed origin is not echoed back on preflight`() =
        testApplication {
            application { module() }

            val response =
                client.options("/v1/puzzles/$validId") {
                    headers {
                        append(HttpHeaders.Origin, "https://evil.example")
                        append(HttpHeaders.AccessControlRequestMethod, "GET")
                    }
                }

            // The browser's contract: no `Access-Control-Allow-Origin` header
            // means the request fails the same-origin policy. The exact status
            // Ktor returns here is not the security boundary; the absence of
            // the allow-origin header is.
            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin]).isNull()
        }

    @Test
    fun `preflight from www prod origin is allowed`() =
        testApplication {
            application { module() }

            val response =
                client.options("/v1/puzzles/$validId") {
                    headers {
                        append(HttpHeaders.Origin, "https://www.wordsparrow.io")
                        append(HttpHeaders.AccessControlRequestMethod, "GET")
                    }
                }

            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin])
                .isEqualTo("https://www.wordsparrow.io")
        }

    @Test
    fun `preflight from local dev origin is allowed`() =
        testApplication {
            application { module() }

            val response =
                client.options("/v1/puzzles/$validId") {
                    headers {
                        append(HttpHeaders.Origin, "http://localhost:5173")
                        append(HttpHeaders.AccessControlRequestMethod, "GET")
                    }
                }

            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin])
                .isEqualTo("http://localhost:5173")
        }

    @Test
    fun `preflight allows arbitrary headers (ADR-0034 wildcard)`() =
        testApplication {
            application { module() }

            // ADR-0034 trades the explicit `allowHeader` list for a
            // wildcard predicate so middleware-added cross-cutting
            // headers (X-Request-Id, X-Session-Id, OTel `traceparent` /
            // `tracestate` / `baggage`, future CSRF, etc.) all pass
            // without a registry edit. This single test exercises the
            // historical incident set in one preflight: any header the
            // browser asks about must come back in
            // `Access-Control-Allow-Headers`. A regression that
            // accidentally re-narrows the allowlist will fail here
            // before it lands in prod.
            val response =
                client.options("/v1/puzzles/$validId/hints") {
                    headers {
                        append(HttpHeaders.Origin, "https://wordsparrow.io")
                        append(HttpHeaders.AccessControlRequestMethod, "POST")
                        append(
                            HttpHeaders.AccessControlRequestHeaders,
                            "Content-Type, X-Session-Id, X-Request-Id, traceparent, tracestate, baggage, X-Foo-Future",
                        )
                    }
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin])
                .isEqualTo("https://wordsparrow.io")
            val allowHeaders =
                response.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().lowercase()
            // Each header the codebase has historically tripped over.
            assertThat(allowHeaders).contains("x-session-id")
            assertThat(allowHeaders).contains("x-request-id")
            assertThat(allowHeaders).contains("traceparent")
            assertThat(allowHeaders).contains("tracestate")
            assertThat(allowHeaders).contains("baggage")
            // The wildcard's whole point: a header the codebase has
            // never seen before still passes. Future-us shipping a
            // new middleware-added header doesn't have to remember
            // anything.
            assertThat(allowHeaders).contains("x-foo-future")
        }

    @Test
    fun `preflight allows DELETE for the session erasure endpoint`() =
        testApplication {
            application { module() }

            val response =
                client.options("/v1/sessions/$validId") {
                    headers {
                        append(HttpHeaders.Origin, "https://wordsparrow.io")
                        append(HttpHeaders.AccessControlRequestMethod, "DELETE")
                    }
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin])
                .isEqualTo("https://wordsparrow.io")
            assertThat(response.headers[HttpHeaders.AccessControlAllowMethods]!!)
                .contains("DELETE")
        }

    @Test
    fun `actual POST from prod origin echoes Allow-Origin (hints, non-simple Content-Type)`() =
        testApplication {
            application { module() }

            // Mirrors the GET case above for the POST /hints path. The
            // browser sends Content-Type: application/json, which the
            // CORS spec classifies as non-simple. Ktor's CORS plugin
            // strips Access-Control-Allow-Origin from the actual (post-
            // preflight) response unless `allowNonSimpleContentTypes =
            // true` is set in Module.kt — see game/api Module.kt:93-102
            // for the canonical write-up. Status code is intentionally
            // not asserted: the puzzle does not exist so the route
            // returns 404; what matters is the header.
            val response =
                client.post("/v1/puzzles/$validId/hints") {
                    headers {
                        append(HttpHeaders.Origin, "https://wordsparrow.io")
                        append("X-Session-Id", validId)
                    }
                    contentType(ContentType.Application.Json)
                    setBody("""{"row":0,"column":0}""")
                }

            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin])
                .isEqualTo("https://wordsparrow.io")
        }

    @Test
    fun `Cloudflare Pages preview origin is NOT in the allowlist`() =
        testApplication {
            application { module() }

            // Previews are frontend-only via MSW per ADR-0007 §5; if a preview
            // ever started hitting the real API we'd want it to fail loud
            // instead of silently working in some environments.
            val response =
                client.options("/v1/puzzles/$validId") {
                    headers {
                        append(
                            HttpHeaders.Origin,
                            "https://deadbeef.bliss-frontend.pages.dev",
                        )
                        append(HttpHeaders.AccessControlRequestMethod, "GET")
                    }
                }

            assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin]).isNull()
        }
}
