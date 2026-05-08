package com.bliss.grid.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
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
 * Wire-path tests for `POST /v1/puzzles/{puzzleId}/validate` via Ktor
 * `testApplication`. Each test bootstraps the puzzle by calling GET first
 * (the in-memory store gets populated on first GET, like in production).
 */
class ValidateRouteTest {
    private val puzzleId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"

    @Test
    fun `responds 200 with solved=false and incorrectCells covering every letter slot when body is empty`() =
        testApplication {
            application { module() }
            client.get("/v1/puzzles/$puzzleId")

            val response =
                client.post("/v1/puzzles/$puzzleId/validate") {
                    headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
                    setBody("""{"filledCells":[]}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertThat(body["solved"]!!.jsonPrimitive.content).isEqualTo("false")
        }

    @Test
    fun `responds 404 puzzle-not-found when puzzleId is unknown`() =
        testApplication {
            application { module() }
            val unknownId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a99"

            val response =
                client.post("/v1/puzzles/$unknownId/validate") {
                    headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
                    setBody("""{"filledCells":[]}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
            assertThat(response.bodyAsText()).contains("puzzle-not-found")
        }

    @Test
    fun `responds 400 invalid-validate-request when letter is not single uppercase A-Z`() =
        testApplication {
            application { module() }
            client.get("/v1/puzzles/$puzzleId")

            val response =
                client.post("/v1/puzzles/$puzzleId/validate") {
                    headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
                    setBody("""{"filledCells":[{"row":0,"column":0,"letter":"ab"}]}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("invalid-validate-request")
        }

    @Test
    fun `responds 400 invalid-puzzle-id for non-uuid path parameter`() =
        testApplication {
            application { module() }

            val response =
                client.post("/v1/puzzles/not-a-uuid/validate") {
                    headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
                    setBody("""{"filledCells":[]}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("invalid-puzzle-id")
        }
}
