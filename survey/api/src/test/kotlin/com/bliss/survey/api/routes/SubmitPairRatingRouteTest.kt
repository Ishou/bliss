package com.bliss.survey.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.bliss.survey.api.WIRE_JSON
import com.bliss.survey.api.auth.SESSION_COOKIE_NAME
import com.bliss.survey.api.auth.SessionMiddleware
import com.bliss.survey.application.usecases.SubmitPairRatingCommand
import com.bliss.survey.application.usecases.SubmitPairRatingResult
import com.bliss.survey.domain.model.CampaignId
import com.bliss.survey.domain.model.PairVerdict
import io.ktor.client.request.cookie
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.util.UUID

class SubmitPairRatingRouteTest {
    private val leftId = UUID.fromString("11111111-1111-7111-8111-111111111111")
    private val rightId = UUID.fromString("22222222-2222-7222-8222-222222222222")
    private val userId = UUID.fromString("33333333-3333-7333-8333-333333333333")
    private val campaignUuid = UUID.fromString("44444444-4444-7444-8444-444444444444")
    private val recorded = SubmitPairRatingResult.Recorded(CampaignId(campaignUuid))

    private fun jsonBody(
        left: String = leftId.toString(),
        right: String = rightId.toString(),
        verdict: String = "LEFT_WINS",
        difficulte: Int = 3,
        latency: Int = 1200,
    ): String =
        "{\"leftItemId\":\"$left\",\"rightItemId\":\"$right\",\"verdict\":\"$verdict\",\"difficulte\":$difficulte,\"latencyMs\":$latency}"

    @Test
    fun `anon LEFT_WINS - 201 with campaignId`() =
        testApplication {
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing { submitPairRatingRoute { recorded } }
            }
            val resp =
                client.post("/v1/ratings/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody())
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.Created)
            assertThat(resp.bodyAsText()).contains("\"campaignId\":\"$campaignUuid\"")
        }

    @Test
    fun `auth happy path forwards user attribute`() =
        testApplication {
            var capturedUserId: UUID? = null
            application {
                install(SessionMiddleware) { verifyCookie = { userId } }
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing {
                    submitPairRatingRoute { cmd: SubmitPairRatingCommand ->
                        capturedUserId = cmd.userId?.value
                        recorded
                    }
                }
            }
            val resp =
                client.post("/v1/ratings/pair") {
                    cookie(SESSION_COOKIE_NAME, "valid-token")
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody(verdict = "BOTH_GOOD"))
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.Created)
            assertThat(capturedUserId).isEqualTo(userId)
        }

    @Test
    fun `SKIP also returns 204`() =
        testApplication {
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing { submitPairRatingRoute { SubmitPairRatingResult.Skipped } }
            }
            val resp =
                client.post("/v1/ratings/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody(verdict = "SKIP"))
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.NoContent)
        }

    @Test
    fun `duplicate pair - 409 conflict`() =
        testApplication {
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing { submitPairRatingRoute { SubmitPairRatingResult.AlreadyExists } }
            }
            val resp =
                client.post("/v1/ratings/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody())
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.Conflict)
        }

    @Test
    fun `item not found - 404 problem`() =
        testApplication {
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing { submitPairRatingRoute { SubmitPairRatingResult.ItemNotFound } }
            }
            val resp =
                client.post("/v1/ratings/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody())
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.NotFound)
        }

    @Test
    fun `mot mismatch - 400 problem`() =
        testApplication {
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing { submitPairRatingRoute { SubmitPairRatingResult.PairMotMismatch } }
            }
            val resp =
                client.post("/v1/ratings/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody())
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(resp.headers["Content-Type"]).isNotNull().contains("application/problem+json")
        }

    @Test
    fun `invalid left item id - 400 before use case`() =
        testApplication {
            var called = false
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing {
                    submitPairRatingRoute {
                        called = true
                        recorded
                    }
                }
            }
            val resp =
                client.post("/v1/ratings/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody(left = "not-a-uuid"))
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(called).isEqualTo(false)
        }

    @Test
    fun `unknown verdict - 400 before use case`() =
        testApplication {
            var called = false
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing {
                    submitPairRatingRoute {
                        called = true
                        recorded
                    }
                }
            }
            val resp =
                client.post("/v1/ratings/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody(verdict = "MAYBE"))
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(called).isEqualTo(false)
        }

    @Test
    fun `difficulte out of range - 400`() =
        testApplication {
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing { submitPairRatingRoute { recorded } }
            }
            val resp =
                client.post("/v1/ratings/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody(difficulte = 9))
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.BadRequest)
        }

    @Test
    fun `latency negative - 400`() =
        testApplication {
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing { submitPairRatingRoute { recorded } }
            }
            val resp =
                client.post("/v1/ratings/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody(latency = -1))
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.BadRequest)
        }

    @Test
    fun `verdict enum round-trips`() {
        assertThat(PairVerdict.valueOf("LEFT_WINS".uppercase())).isEqualTo(PairVerdict.LEFT_WINS)
        assertThat(PairVerdict.valueOf("BOTH_GOOD".uppercase())).isEqualTo(PairVerdict.BOTH_GOOD)
    }

    @Test
    fun `returns 423 when the use case returns Locked`() =
        testApplication {
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing { submitPairRatingRoute { SubmitPairRatingResult.Locked } }
            }
            val resp =
                client.post("/v1/ratings/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody())
                }
            assertThat(resp.status.value).isEqualTo(423)
            assertThat(resp.bodyAsText()).contains("\"title\":\"campaign closed\"")
        }
}
