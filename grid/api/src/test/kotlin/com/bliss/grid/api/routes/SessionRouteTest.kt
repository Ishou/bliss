package com.bliss.grid.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import com.bliss.grid.api.module
import io.ktor.client.request.delete
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
 * Wire-path tests for `DELETE /v1/sessions/{sessionId}` via Ktor `testApplication`.
 * Exercises the full stack (DI + use case + in-memory hint repository + serialization).
 */
class SessionRouteTest {
    private val puzzleId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"
    private val sessionId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c"

    @Test
    fun `responds 200 with deleted = 0 when session has no data`() =
        testApplication {
            application { module() }

            val response = client.delete("/v1/sessions/$sessionId")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertThat(body["deleted"]!!.jsonPrimitive.content.toInt()).isEqualTo(0)
        }

    @Test
    fun `responds 200 with deleted greater than 0 after the session used a hint`() =
        testApplication {
            application { module() }
            // Bootstrap the puzzle and spend one hint to seed a hint_usage row.
            client.get("/v1/puzzles/$puzzleId")
            client.post("/v1/puzzles/$puzzleId/hints") {
                headers {
                    append("X-Session-Id", sessionId)
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                }
                setBody("""{"word":"chien"}""")
            }

            val response = client.delete("/v1/sessions/$sessionId")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertThat(body["deleted"]!!.jsonPrimitive.content.toInt()).isEqualTo(1)
        }

    @Test
    fun `is idempotent — calling twice returns 0 the second time`() =
        testApplication {
            application { module() }
            client.get("/v1/puzzles/$puzzleId")
            client.post("/v1/puzzles/$puzzleId/hints") {
                headers {
                    append("X-Session-Id", sessionId)
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                }
                setBody("""{"word":"chien"}""")
            }

            val first = client.delete("/v1/sessions/$sessionId")
            val second = client.delete("/v1/sessions/$sessionId")

            val firstBody = Json.parseToJsonElement(first.bodyAsText()).jsonObject
            val secondBody = Json.parseToJsonElement(second.bodyAsText()).jsonObject
            assertThat(firstBody["deleted"]!!.jsonPrimitive.content.toInt()).isEqualTo(1)
            assertThat(secondBody["deleted"]!!.jsonPrimitive.content.toInt()).isEqualTo(0)
        }

    @Test
    fun `responds 400 invalid-session-id when sessionId is not a UUID`() =
        testApplication {
            application { module() }

            val response = client.delete("/v1/sessions/not-a-uuid")

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.headers["Content-Type"]!!).startsWith("application/problem+json")
            assertThat(response.bodyAsText()).contains("invalid-session-id")
        }
}
