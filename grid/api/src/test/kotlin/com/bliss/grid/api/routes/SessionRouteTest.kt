package com.bliss.grid.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import com.bliss.grid.api.module
import io.ktor.client.request.delete
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Wire-path tests for `DELETE /v1/sessions/{sessionId}` via Ktor `testApplication`.
 *
 * After Phase 6b.1 hint-usage rows are no longer session-keyed; the
 * delete-session endpoint only clears clue-cooldown rows (when the
 * feature is wired) and is now a no-op for in-memory module wiring
 * with no cooldown repository.
 */
class SessionRouteTest {
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
    fun `is idempotent`() =
        testApplication {
            application { module() }

            val first = client.delete("/v1/sessions/$sessionId")
            val second = client.delete("/v1/sessions/$sessionId")

            assertThat(first.status).isEqualTo(HttpStatusCode.OK)
            assertThat(second.status).isEqualTo(HttpStatusCode.OK)
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
