package com.bliss.grid.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.matches
import assertk.assertions.startsWith
import com.bliss.grid.api.module
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
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
    fun `responds 200 with the canonical letter and decremented budget`() =
        testApplication {
            application { module() }
            val (row, column) = bootstrapAndPickLetterCell(client)

            val response = revealCell(client, row, column)

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertThat(body["row"]!!.jsonPrimitive.content.toInt()).isEqualTo(row)
            assertThat(body["column"]!!.jsonPrimitive.content.toInt()).isEqualTo(column)
            // letter is a single uppercase A-Z code point.
            assertThat(body["letter"]!!.jsonPrimitive.content).matches(Regex("^[A-Z]$"))
            // Default hintsAllowed = 3; we just spent one.
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
                    setBody("""{"row":0,"column":0}""")
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
                    setBody("""{"row":0,"column":0}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("invalid-session-id")
        }

    @Test
    fun `responds 400 invalid-coord when row is out of grid bounds`() =
        testApplication {
            application { module() }
            client.get("/v1/puzzles/$puzzleId")

            val response = revealCell(client, row = 999, column = 0)

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("invalid-coord")
        }

    @Test
    fun `responds 400 invalid-coord when coordinate points at a clue cell`() =
        testApplication {
            application { module() }
            val (clueRow, clueColumn) = bootstrapAndPickClueCell(client)

            val response = revealCell(client, clueRow, clueColumn)

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("invalid-coord")
        }

    @Test
    fun `invalid-coord does not decrement the budget`() =
        testApplication {
            application { module() }
            val (row, column) = bootstrapAndPickLetterCell(client)

            // Burn an out-of-bounds reveal; budget must stay at 3.
            revealCell(client, row = 999, column = 999)

            // First valid reveal should still see hintsRemaining = 2.
            val response = revealCell(client, row, column)
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertThat(body["hintsRemaining"]!!.jsonPrimitive.content.toInt()).isEqualTo(2)
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
                    setBody("""{"row":0,"column":0}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
            assertThat(response.bodyAsText()).contains("puzzle-not-found")
        }

    @Test
    fun `responds 429 hint-budget-exhausted after 3 spends with default cap`() =
        testApplication {
            application { module() }
            val (row, column) = bootstrapAndPickLetterCell(client)

            // Default hintsAllowed = 3; 4th call exhausts.
            repeat(3) {
                val ok = revealCell(client, row, column)
                assertThat(ok.status).isEqualTo(HttpStatusCode.OK)
            }
            val exhausted = revealCell(client, row, column)
            assertThat(exhausted.status).isEqualTo(HttpStatusCode.TooManyRequests)
            assertThat(exhausted.bodyAsText()).contains("hint-budget-exhausted")
        }

    private suspend fun revealCell(
        client: HttpClient,
        row: Int,
        column: Int,
    ): HttpResponse =
        client.post("/v1/puzzles/$puzzleId/hints") {
            headers {
                append("X-Session-Id", sessionId)
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody("""{"row":$row,"column":$column}""")
        }

    private suspend fun bootstrapAndPickLetterCell(client: HttpClient): Pair<Int, Int> = bootstrapAndPickCell(client, "letter")

    private suspend fun bootstrapAndPickClueCell(client: HttpClient): Pair<Int, Int> = bootstrapAndPickCell(client, "definition")

    private suspend fun bootstrapAndPickCell(
        client: HttpClient,
        kind: String,
    ): Pair<Int, Int> {
        val response = client.get("/v1/puzzles/$puzzleId")
        val cells = Json.parseToJsonElement(response.bodyAsText()).jsonObject["cells"]!!.jsonArray
        return firstCellOfKind(cells, kind)
    }

    private fun firstCellOfKind(
        cells: JsonArray,
        kind: String,
    ): Pair<Int, Int> {
        for (element in cells) {
            val cell = element.jsonObject
            if (cell["kind"]!!.jsonPrimitive.content == kind) {
                val position = cell["position"]!!.jsonObject
                return position["row"]!!.jsonPrimitive.content.toInt() to
                    position["column"]!!.jsonPrimitive.content.toInt()
            }
        }
        error("no cell of kind '$kind' in puzzle response: $cells")
    }
}
