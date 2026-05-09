package com.bliss.grid.api

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.matches
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

class CorrelationIdTest {
    @Test
    fun `echoes client X-Request-Id back in response header`() =
        testApplication {
            application { module() }

            val response =
                client.get("/v1/health") {
                    header("X-Request-Id", "test-id")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers["X-Request-Id"]).isEqualTo("test-id")
        }

    @Test
    fun `generates server-side UUID when X-Request-Id is absent`() =
        testApplication {
            application { module() }

            val response = client.get("/v1/health")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val id = response.headers["X-Request-Id"]
            assertThat(id).isNotNull()
            assertThat(id!!).matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
        }

    @Test
    fun `ignores oversized X-Request-Id and generates server-side UUID`() =
        testApplication {
            application { module() }

            val oversized = "x".repeat(129)
            val response =
                client.get("/v1/health") {
                    header("X-Request-Id", oversized)
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val id = response.headers["X-Request-Id"]
            assertThat(id).isNotNull()
            assertThat(id).isNotEqualTo(oversized)
            assertThat(id!!).matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
        }
}
