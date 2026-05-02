package com.bliss.grid.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import com.bliss.grid.api.dto.PuzzleResponse
import com.bliss.grid.api.module
import com.bliss.grid.application.puzzle.GeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.PUZZLE_HEIGHT
import com.bliss.grid.application.puzzle.PUZZLE_WIDTH
import com.bliss.grid.application.puzzle.defaultPuzzleConstraints
import com.bliss.grid.domain.generation.WordRepository
import com.bliss.grid.domain.model.Word
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/** Wire-path tests for `GET /v1/puzzles/{puzzleId}` via Ktor [testApplication]. */
class PuzzleRouteTest {
    private val validId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"

    @Test
    fun `responds 200 with a puzzle whose body matches the OpenAPI shape`() =
        testApplication {
            application { module() }

            val response = client.get("/v1/puzzles/$validId")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers["Content-Type"]!!).startsWith("application/json")

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertThat(json["id"]!!.jsonPrimitive.content).isEqualTo(validId)
            assertThat(json["width"]!!.jsonPrimitive.content.toInt()).isEqualTo(PUZZLE_WIDTH)
            assertThat(json["height"]!!.jsonPrimitive.content.toInt()).isEqualTo(PUZZLE_HEIGHT)
            // ClueCells with two stacked clues emit two DefinitionCellDtos at
            // the same position, so cells.length is >= width * height.
            assertThat(json["cells"]!!.jsonArray.size).isGreaterThanOrEqualTo(PUZZLE_WIDTH * PUZZLE_HEIGHT)
        }

    @Test
    fun `every DefinitionCell clueId resolves in the clues array, which is non-empty`() =
        testApplication {
            application { module() }

            val json =
                Json
                    .parseToJsonElement(client.get("/v1/puzzles/$validId").bodyAsText())
                    .jsonObject
            val clues = json["clues"]!!.jsonArray
            val cells = json["cells"]!!.jsonArray
            val clueIds = clues.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()

            assertThat(clues.size).isGreaterThan(0)
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
    fun `cells emit non-decreasing row-major positions, all within bounds`() =
        testApplication {
            application { module() }

            val json =
                Json
                    .parseToJsonElement(client.get("/v1/puzzles/$validId").bodyAsText())
                    .jsonObject
            val cells = json["cells"]!!.jsonArray
            val width = json["width"]!!.jsonPrimitive.content.toInt()
            val height = json["height"]!!.jsonPrimitive.content.toInt()

            var lastIndex = -1
            cells.forEach { cell ->
                val pos = (cell as JsonObject)["position"]!!.jsonObject
                val row = pos["row"]!!.jsonPrimitive.content.toInt()
                val col = pos["column"]!!.jsonPrimitive.content.toInt()
                assertThat(row in 0 until height).isTrue()
                assertThat(col in 0 until width).isTrue()
                val flat = row * width + col
                assertThat(flat).isGreaterThanOrEqualTo(lastIndex)
                lastIndex = flat
            }
        }

    @Test
    fun `responds 400 with problem json for non-uuid puzzle id`() =
        testApplication {
            application { module() }

            val response = client.get("/v1/puzzles/not-a-uuid")

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.headers["Content-Type"]!!).startsWith("application/problem+json")
            assertThat(response.bodyAsText()).contains("\"status\":400")
        }

    @Test
    fun `consecutive GETs produce different cell layouts - randomness end-to-end`() =
        testApplication {
            application { module() }

            val secondId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c"
            val first = client.get("/v1/puzzles/$validId").bodyAsText()
            val second = client.get("/v1/puzzles/$secondId").bodyAsText()

            val firstCells =
                Json
                    .parseToJsonElement(first)
                    .jsonObject["cells"]!!
                    .jsonArray
                    .toString()
            val secondCells =
                Json
                    .parseToJsonElement(second)
                    .jsonObject["cells"]!!
                    .jsonArray
                    .toString()

            assertThat(firstCells).isNotEqualTo(secondCells)
        }

    @Test
    fun `response body deserializes as PuzzleResponse - schema drift guard`() =
        testApplication {
            application { module() }
            val body = client.get("/v1/puzzles/$validId").bodyAsText()
            // Throws SerializationException if wire shape diverges from the DTO (ADR-0003 §9).
            val puzzle = Json { ignoreUnknownKeys = true }.decodeFromString<PuzzleResponse>(body)
            assertThat(puzzle.id).isEqualTo(validId)
        }

    @Test
    fun `accepts width and height query params and reflects them in the response`() =
        testApplication {
            application { module() }

            val response = client.get("/v1/puzzles/$validId?width=7&height=7")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertThat(json["width"]!!.jsonPrimitive.content.toInt()).isEqualTo(7)
            assertThat(json["height"]!!.jsonPrimitive.content.toInt()).isEqualTo(7)
        }

    @Test
    fun `responds 400 with invalid-puzzle-dimensions when width is below the minimum`() =
        testApplication {
            application { module() }

            val response = client.get("/v1/puzzles/$validId?width=4") // one below the spec minimum of 5

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.headers["Content-Type"]!!).startsWith("application/problem+json")
            val body = response.bodyAsText()
            assertThat(body).contains("invalid-puzzle-dimensions")
            assertThat(body).contains("\"status\":400")
        }

    @Test
    fun `responds 400 with invalid-puzzle-dimensions when height is above the maximum`() =
        testApplication {
            application { module() }

            val response = client.get("/v1/puzzles/$validId?height=16") // one above the spec maximum of 15

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("invalid-puzzle-dimensions")
        }

    @Test
    fun `responds 400 with invalid-puzzle-dimensions when width is not an integer`() =
        testApplication {
            application { module() }

            val response = client.get("/v1/puzzles/$validId?width=abc")

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("invalid-puzzle-dimensions")
        }

    @Test
    fun `responds 422 with problem json when generator cannot satisfy constraints`() =
        testApplication {
            application {
                val emptyRepo =
                    object : WordRepository {
                        override fun findByLength(length: Int): List<Word> = emptyList()

                        override fun findByLengthAndPattern(
                            length: Int,
                            pattern: Map<Int, Char>,
                        ): List<Word> = emptyList()
                    }
                routing {
                    puzzles(GeneratePuzzleUseCase(emptyRepo, defaultPuzzleConstraints()))
                }
            }

            val response = client.get("/v1/puzzles/$validId")

            assertThat(response.status).isEqualTo(HttpStatusCode.UnprocessableEntity)
            assertThat(response.headers["Content-Type"]!!).startsWith("application/problem+json")
            assertThat(response.bodyAsText()).contains("puzzle-generation-failed")
        }
}
