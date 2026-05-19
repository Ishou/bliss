package com.bliss.grid.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.matches
import assertk.assertions.startsWith
import com.bliss.grid.application.auth.WhoAmI
import com.bliss.grid.application.puzzle.GeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.LoadOrGeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.RevealCellHintUseCase
import com.bliss.grid.application.puzzle.ValidatePuzzleUseCase
import com.bliss.grid.application.puzzle.defaultPuzzleConstraints
import com.bliss.grid.infrastructure.persistence.CsvWordRepository
import com.bliss.grid.infrastructure.persistence.InMemoryHintUsageRepository
import com.bliss.grid.infrastructure.persistence.InMemoryHintWriteCoordinator
import com.bliss.grid.infrastructure.persistence.InMemoryPuzzleRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID

/** Wire-path tests for `POST /v1/puzzles/{puzzleId}/hints` via Ktor `testApplication`. */
class HintsRouteTest {
    private val puzzleId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"
    private val userId = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c")
    private val cookieValue = "session-cookie-value"

    private fun io.ktor.server.testing.ApplicationTestBuilder.mountWith(verifier: FakeCookieVerifier) {
        application {
            install(ContentNegotiation) { json() }
            val puzzleRepo = InMemoryPuzzleRepository()
            val hintUsageRepo = InMemoryHintUsageRepository()
            val gen = GeneratePuzzleUseCase(CsvWordRepository.frenchFromClasspath(), defaultPuzzleConstraints())
            routing {
                puzzles(
                    loadOrGenerate = LoadOrGeneratePuzzleUseCase(puzzleRepo, gen),
                    revealCellHint = RevealCellHintUseCase(puzzleRepo, hintUsageRepo),
                    validatePuzzle = ValidatePuzzleUseCase(puzzleRepo),
                    puzzleRepository = puzzleRepo,
                    hintUsageRepository = hintUsageRepo,
                    hintWriteCoordinator = InMemoryHintWriteCoordinator(),
                    cookieVerifier = verifier,
                )
            }
        }
    }

    @Test
    fun `responds 200 with the canonical letter and decremented budget`() =
        testApplication {
            mountWith(FakeCookieVerifier(cached = WhoAmI(userId, "Joueuse")))
            val (row, column) = bootstrapAndPickLetterCell(client)

            val response = revealCell(client, row, column)

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertThat(body["row"]!!.jsonPrimitive.content.toInt()).isEqualTo(row)
            assertThat(body["column"]!!.jsonPrimitive.content.toInt()).isEqualTo(column)
            assertThat(body["letter"]!!.jsonPrimitive.content).matches(Regex("^[A-Z]$"))
            // Default hintsAllowed = 3; we just spent one.
            assertThat(body["hintsRemaining"]!!.jsonPrimitive.content.toInt()).isEqualTo(2)
        }

    @Test
    fun `responds 401 auth-required when the cookie is missing`() =
        testApplication {
            mountWith(FakeCookieVerifier(cached = WhoAmI(userId, "Joueuse")))

            val response =
                client.post("/v1/puzzles/$puzzleId/hints") {
                    headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
                    setBody("""{"row":0,"column":0}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(response.headers["Content-Type"]!!).startsWith("application/problem+json")
            assertThat(response.bodyAsText()).contains("auth-required")
        }

    @Test
    fun `responds 401 auth-required when verifyFresh returns null even though verify cached a positive`() =
        testApplication {
            // verify (cached) returns a WhoAmI but verifyFresh returns null — session was revoked
            // between read and write. The under-lock fresh check catches it.
            mountWith(FakeCookieVerifier(cached = WhoAmI(userId, "Joueuse"), fresh = null))
            val (row, column) = bootstrapAndPickLetterCell(client)

            val response = revealCell(client, row, column)

            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(response.bodyAsText()).contains("auth-required")
        }

    @Test
    fun `responds 400 invalid-coord when row is out of grid bounds`() =
        testApplication {
            mountWith(FakeCookieVerifier(cached = WhoAmI(userId, "Joueuse")))
            client.get("/v1/puzzles/$puzzleId")

            val response = revealCell(client, row = 999, column = 0)

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("invalid-coord")
        }

    @Test
    fun `responds 400 invalid-coord when coordinate points at a clue cell`() =
        testApplication {
            mountWith(FakeCookieVerifier(cached = WhoAmI(userId, "Joueuse")))
            val (clueRow, clueColumn) = bootstrapAndPickClueCell(client)

            val response = revealCell(client, clueRow, clueColumn)

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("invalid-coord")
        }

    @Test
    fun `invalid-coord does not decrement the budget`() =
        testApplication {
            mountWith(FakeCookieVerifier(cached = WhoAmI(userId, "Joueuse")))
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
            mountWith(FakeCookieVerifier(cached = WhoAmI(userId, "Joueuse")))
            val unknownId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a99"

            val response =
                client.post("/v1/puzzles/$unknownId/hints") {
                    cookie("__Secure-ws_session", cookieValue)
                    headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
                    setBody("""{"row":0,"column":0}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
            assertThat(response.bodyAsText()).contains("puzzle-not-found")
        }

    @Test
    fun `responds 429 hint-budget-exhausted after 3 spends with default cap`() =
        testApplication {
            mountWith(FakeCookieVerifier(cached = WhoAmI(userId, "Joueuse")))
            val (row, column) = bootstrapAndPickLetterCell(client)

            repeat(3) {
                val ok = revealCell(client, row, column)
                assertThat(ok.status).isEqualTo(HttpStatusCode.OK)
            }
            val exhausted = revealCell(client, row, column)
            assertThat(exhausted.status).isEqualTo(HttpStatusCode.TooManyRequests)
            assertThat(exhausted.bodyAsText()).contains("hint-budget-exhausted")
        }

    @Test
    fun `GET puzzle with cookie embeds hintsRemaining reflecting prior spends`() =
        testApplication {
            mountWith(FakeCookieVerifier(cached = WhoAmI(userId, "Joueuse")))
            val (row, column) = bootstrapAndPickLetterCell(client)

            // Spend one hint.
            revealCell(client, row, column)

            val getResponse =
                client.get("/v1/puzzles/$puzzleId") {
                    cookie("__Secure-ws_session", cookieValue)
                }
            val body = Json.parseToJsonElement(getResponse.bodyAsText()).jsonObject
            assertThat(body["hintsAllowed"]!!.jsonPrimitive.content.toInt()).isEqualTo(3)
            assertThat(body["hintsRemaining"]!!.jsonPrimitive.content.toInt()).isEqualTo(2)
        }

    @Test
    fun `GET puzzle without cookie reports hintsRemaining equal to hintsAllowed`() =
        testApplication {
            mountWith(FakeCookieVerifier(cached = WhoAmI(userId, "Joueuse")))
            val getResponse = client.get("/v1/puzzles/$puzzleId")
            val body = Json.parseToJsonElement(getResponse.bodyAsText()).jsonObject
            assertThat(body["hintsAllowed"]!!.jsonPrimitive.content.toInt()).isEqualTo(3)
            assertThat(body["hintsRemaining"]!!.jsonPrimitive.content.toInt()).isEqualTo(3)
        }

    private suspend fun revealCell(
        client: HttpClient,
        row: Int,
        column: Int,
    ): HttpResponse =
        client.post("/v1/puzzles/$puzzleId/hints") {
            cookie("__Secure-ws_session", cookieValue)
            headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
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
