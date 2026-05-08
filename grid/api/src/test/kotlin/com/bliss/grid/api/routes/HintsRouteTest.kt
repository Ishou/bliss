package com.bliss.grid.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import com.bliss.grid.api.module
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Wire-path tests for `POST /v1/puzzles/{puzzleId}/hints` via Ktor `testApplication`.
 *
 * Each test runs through `module()` against the in-memory adapters so the
 * full stack (DI wiring + serialization + RFC 7807 errors) is exercised. The
 * preceding GET on the same puzzleId is necessary to populate the puzzle
 * store — hints on an unknown puzzle return 404.
 */
class HintsRouteTest {
    private val puzzleId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"
    private val sessionId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c"

    @Test
    fun `responds 200 with hintsRemaining when word is in corpus`() =
        testApplication {
            application { module() }
            // Bootstrap the puzzle into the store.
            client.get("/v1/puzzles/$puzzleId")

            val response =
                client.post("/v1/puzzles/$puzzleId/hints") {
                    headers {
                        append("X-Session-Id", sessionId)
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"word":"chien"}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertThat(body["word"]!!.jsonPrimitive.content).isEqualTo("chien")
            // hintsRemaining is allowed - 1 (just spent one hint, default cap = 3)
            assertThat(body["hintsRemaining"]!!.jsonPrimitive.content.toInt()).isEqualTo(2)
        }

    @Test
    fun `responds 400 invalid-session-id when X-Session-Id header is missing`() =
        testApplication {
            application { module() }
            client.get("/v1/puzzles/$puzzleId")

            val response =
                client.post("/v1/puzzles/$puzzleId/hints") {
                    headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
                    setBody("""{"word":"chien"}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.headers["Content-Type"]!!).startsWith("application/problem+json")
            assertThat(response.bodyAsText()).contains("invalid-session-id")
        }

    @Test
    fun `responds 400 invalid-session-id when X-Session-Id is not a UUID`() =
        testApplication {
            application { module() }
            client.get("/v1/puzzles/$puzzleId")

            val response =
                client.post("/v1/puzzles/$puzzleId/hints") {
                    headers {
                        append("X-Session-Id", "not-a-uuid")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"word":"chien"}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("invalid-session-id")
        }

    @Test
    fun `responds 400 invalid-word when word is too short`() =
        testApplication {
            application { module() }
            client.get("/v1/puzzles/$puzzleId")

            val response =
                client.post("/v1/puzzles/$puzzleId/hints") {
                    headers {
                        append("X-Session-Id", sessionId)
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"word":"a"}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("invalid-word")
        }

    @Test
    fun `responds 404 puzzle-not-found when puzzleId is unknown`() =
        testApplication {
            application { module() }
            // No GET first; puzzle store is empty for this id.
            val unknownId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a99"

            val response =
                client.post("/v1/puzzles/$unknownId/hints") {
                    headers {
                        append("X-Session-Id", sessionId)
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"word":"chien"}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
            assertThat(response.bodyAsText()).contains("puzzle-not-found")
        }

    @Test
    fun `responds 429 hint-budget-exhausted after 3 spends with default cap`() =
        testApplication {
            application { module() }
            client.get("/v1/puzzles/$puzzleId")

            // Default hintsAllowed = 3; 4th call exhausts.
            repeat(3) {
                val ok =
                    client.post("/v1/puzzles/$puzzleId/hints") {
                        headers {
                            append("X-Session-Id", sessionId)
                            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        }
                        setBody("""{"word":"chien"}""")
                    }
                assertThat(ok.status).isEqualTo(HttpStatusCode.OK)
            }
            val exhausted =
                client.post("/v1/puzzles/$puzzleId/hints") {
                    headers {
                        append("X-Session-Id", sessionId)
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"word":"chien"}""")
                }
            assertThat(exhausted.status).isEqualTo(HttpStatusCode.TooManyRequests)
            assertThat(exhausted.bodyAsText()).contains("hint-budget-exhausted")
        }
}
