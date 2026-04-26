package com.bliss.grid.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import com.bliss.grid.api.module
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/** Wire-path tests for `GET /v1/puzzles/{puzzleId}` via Ktor [testApplication]. */
class PuzzleRouteTest {
    private val validPuzzleId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"

    @Test
    fun `responds 200 with application json for a valid uuid`() =
        testApplication {
            application { module() }

            val response = client.get("/v1/puzzles/$validPuzzleId")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val ct = response.headers["Content-Type"]
            assertThat(ct).isNotNull()
            assertThat(ct!!).startsWith("application/json")
        }

    @Test
    fun `body has 10x10 dimensions and at least 100 cells`() =
        testApplication {
            application { module() }

            val body = client.get("/v1/puzzles/$validPuzzleId").bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject

            assertThat(json["width"]?.jsonPrimitive?.content?.toInt()).isEqualTo(10)
            assertThat(json["height"]?.jsonPrimitive?.content?.toInt()).isEqualTo(10)
            val cells = json["cells"]?.jsonArray
            assertThat(cells).isNotNull()
            // ClueCells with two stacked clues emit two DefinitionCellDtos at
            // the same position, so count is >= width*height (per task spec).
            assertThat(cells!!.size).isGreaterThanOrEqualTo(100)
        }

    @Test
    fun `body echoes the requested puzzle id`() =
        testApplication {
            application { module() }

            val body = client.get("/v1/puzzles/$validPuzzleId").bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject

            assertThat(json["id"]?.jsonPrimitive?.content).isEqualTo(validPuzzleId)
        }

    @Test
    fun `clues array is non-empty and has required fields`() =
        testApplication {
            application { module() }

            val body = client.get("/v1/puzzles/$validPuzzleId").bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val clues = json["clues"]?.jsonArray

            assertThat(clues).isNotNull()
            assertThat(clues!!.size).isGreaterThan(0)
            clues.forEach { clue ->
                val obj = clue.jsonObject
                assertThat(obj["id"]).isNotNull()
                assertThat(obj["direction"]).isNotNull()
                assertThat(obj["start"]).isNotNull()
                assertThat(obj["length"]).isNotNull()
                assertThat(obj["text"]).isNotNull()
            }
        }

    @Test
    fun `every definition cell clueId resolves to a clue id in clues array`() =
        testApplication {
            application { module() }

            val body = client.get("/v1/puzzles/$validPuzzleId").bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val cells = json["cells"]!!.jsonArray
            val clues = json["clues"]!!.jsonArray

            val clueIds = clues.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
            val referenced =
                cells
                    .map { it.jsonObject }
                    .filter { it["kind"]?.jsonPrimitive?.content == "definition" }
                    .map { it["clueId"]!!.jsonPrimitive.content }

            assertThat(referenced.size).isGreaterThan(0)
            referenced.forEach { id ->
                assertThat(clueIds.contains(id)).isTrue()
            }
        }

    @Test
    fun `responds 400 with problem json for non-uuid puzzle id`() =
        testApplication {
            application { module() }

            val response = client.get("/v1/puzzles/not-a-uuid")

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            val ct = response.headers["Content-Type"]
            assertThat(ct).isNotNull()
            assertThat(ct!!).startsWith("application/problem+json")
            val body = response.bodyAsText()
            assertThat(body).contains("\"status\":400")
        }

    @Test
    fun `cells positions are emitted in non-decreasing row-major order`() =
        testApplication {
            application { module() }

            val body = client.get("/v1/puzzles/$validPuzzleId").bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val cells: JsonArray = json["cells"]!!.jsonArray
            val width = json["width"]!!.jsonPrimitive.content.toInt()
            val height = json["height"]!!.jsonPrimitive.content.toInt()

            assertThat(cells.size).isGreaterThanOrEqualTo(width * height)
            // Row-major: position(i+1) >= position(i) by row*width+column ordering.
            // Equality is allowed: ClueCells with two stacked clues emit two
            // adjacent DefinitionCellDtos at the same position.
            var lastIndex = -1
            cells.forEach { cell ->
                val pos = (cell as JsonObject)["position"]!!.jsonObject
                val row = pos["row"]!!.jsonPrimitive.content.toInt()
                val col = pos["column"]!!.jsonPrimitive.content.toInt()
                assertThat(row in 0 until height).isTrue()
                assertThat(col in 0 until width).isTrue()
                val flatIndex = row * width + col
                assertThat(flatIndex).isGreaterThanOrEqualTo(lastIndex)
                lastIndex = flatIndex
            }
        }
}
