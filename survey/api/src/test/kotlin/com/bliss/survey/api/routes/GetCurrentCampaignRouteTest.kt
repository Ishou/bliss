package com.bliss.survey.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.bliss.survey.api.WIRE_JSON
import com.bliss.survey.api.dto.CampaignResponse
import com.bliss.survey.domain.model.Campaign
import com.bliss.survey.domain.model.CampaignId
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GetCurrentCampaignRouteTest {
    private val openedAt = Instant.parse("2026-05-30T10:00:00Z")

    @Test
    fun `returns 200 with the open campaign`() =
        testApplication {
            val campaign =
                Campaign(
                    id = CampaignId(UUID.fromString("00000000-0000-0000-0000-000000000007")),
                    batchLabel = "round-7",
                    openedAt = openedAt,
                    closedAt = null,
                )
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing { getCurrentCampaignRoute { campaign } }
            }
            val response = client.get("/v1/campaign/current")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = WIRE_JSON.decodeFromString(CampaignResponse.serializer(), response.bodyAsText())
            assertThat(body.campaignId).isEqualTo("00000000-0000-0000-0000-000000000007")
            assertThat(body.batchLabel).isEqualTo("round-7")
            assertThat(body.closedAt).isEqualTo(null)
        }

    @Test
    fun `returns 200 with a closed campaign when no campaign is open`() =
        testApplication {
            val campaign =
                Campaign(
                    id = CampaignId(UUID.randomUUID()),
                    batchLabel = "round-6",
                    openedAt = openedAt,
                    closedAt = openedAt.plusSeconds(3600),
                )
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing { getCurrentCampaignRoute { campaign } }
            }
            val response = client.get("/v1/campaign/current")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.bodyAsText()).contains("\"closedAt\":")
        }

    @Test
    fun `returns 503 when no campaign exists`() =
        testApplication {
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing { getCurrentCampaignRoute { null } }
            }
            val response = client.get("/v1/campaign/current")
            assertThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
        }
}
