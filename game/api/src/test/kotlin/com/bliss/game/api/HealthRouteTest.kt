package com.bliss.game.api

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.matches
import assertk.assertions.startsWith
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/** Wire-path tests for `/v1/health` — exercises the route via Ktor [testApplication]. */
class HealthRouteTest {
    @Test
    fun `responds with 200 and application json`() =
        testApplication {
            application { module() }

            val response = client.get("/v1/health")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            // ContentNegotiation appends charset; assert the media-type prefix only.
            val contentType = response.headers["Content-Type"]
            assertThat(contentType).isNotNull()
            assertThat(contentType!!).startsWith(ContentType.Application.Json.toString())
        }

    @Test
    fun `body has ok status, version, and ISO-8601 uptime`() =
        testApplication {
            application { module() }

            val body = client.get("/v1/health").bodyAsText()
            val json = Json.parseToJsonElement(body) as JsonObject

            assertThat(json["status"]?.jsonPrimitive?.content).isEqualTo("ok")
            // Version is the compile-time constant from version.kt; bumps in lockstep
            // with build.gradle.kts. If this fails after a release bump, update both.
            assertThat(json["version"]?.jsonPrimitive?.content).isEqualTo(APP_VERSION)

            val uptime = json["uptime"]?.jsonPrimitive?.content
            assertThat(uptime).isNotNull()
            assertThat(uptime!!).matches(Regex("PT\\d+S"))
        }
}
