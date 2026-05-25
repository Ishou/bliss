package com.bliss.survey.api.routes

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

class HealthRouteTest {
    @Test
    fun `returns 200 ok with status payload`() =
        testApplication {
            application {
                routing { healthRoute() }
            }
            val resp = client.get("/v1/health")
            assertThat(resp.status).isEqualTo(HttpStatusCode.OK)
            assertThat(resp.bodyAsText()).isEqualTo("""{"status":"ok"}""")
        }
}
