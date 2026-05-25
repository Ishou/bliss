package com.bliss.survey.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.bliss.survey.api.WIRE_JSON
import com.bliss.survey.api.auth.SESSION_COOKIE_NAME
import com.bliss.survey.api.auth.SessionMiddleware
import com.bliss.survey.application.usecases.SubmitRatingCommand
import com.bliss.survey.application.usecases.SubmitRatingResult
import com.bliss.survey.domain.model.FlagReason
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Rating
import com.bliss.survey.domain.model.RatingId
import com.bliss.survey.domain.model.SubmittedAs
import com.bliss.survey.domain.model.UserId
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
import java.time.Instant
import java.util.UUID

class SubmitRatingRouteTest {
    private val itemUuid = UUID.fromString("11111111-1111-7111-8111-111111111111")
    private val ratingUuid = UUID.fromString("22222222-2222-7222-8222-222222222222")
    private val userUuid = UUID.fromString("33333333-3333-7333-8333-333333333333")

    private val acceptedAnon =
        Rating(
            id = RatingId(ratingUuid),
            itemId = ItemId(itemUuid),
            userId = null,
            submittedAs = SubmittedAs.ANON,
            qualite = 4,
            difficulte = 2,
            flag = null,
            proposedItemId = null,
            latencyMs = 1200,
            createdAt = Instant.parse("2026-05-25T12:00:00Z"),
        )

    private val acceptedAuth =
        acceptedAnon.copy(userId = UserId(userUuid), submittedAs = SubmittedAs.AUTH)

    private fun jsonBody(
        qualite: Int = 4,
        difficulte: Int = 2,
        flag: String? = null,
        correctif: String? = null,
        latency: Int = 1200,
    ): String =
        buildString {
            append("{\"qualite\":$qualite,\"difficulte\":$difficulte,")
            if (flag != null) append("\"flag\":\"$flag\",")
            if (correctif != null) append("\"correctif\":{\"text\":\"$correctif\",\"style\":\"definition_directe\"},")
            append("\"latencyMs\":$latency}")
        }

    @Test
    fun `anon happy path - 201 created`() =
        testApplication {
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing { submitRatingRoute { SubmitRatingResult.Accepted(acceptedAnon) } }
            }
            val resp =
                client.post("/v1/items/$itemUuid/rating") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody())
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.Created)
            val body = resp.bodyAsText()
            assertThat(body).contains("\"submittedAs\":\"anon\"")
            assertThat(body).contains("\"ratingId\":\"$ratingUuid\"")
            // required-nullable: must appear as null, not be absent (ADR-0003 §6)
            assertThat(body).contains("\"proposedItemId\":null")
        }

    @Test
    fun `auth happy path - 201 with user attribute resolved`() =
        testApplication {
            application {
                install(SessionMiddleware) { verifyCookie = { userUuid } }
                install(ContentNegotiation) { json() }
                routing { submitRatingRoute { SubmitRatingResult.Accepted(acceptedAuth) } }
            }
            val resp =
                client.post("/v1/items/$itemUuid/rating") {
                    cookie(SESSION_COOKIE_NAME, "valid-token")
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody(flag = "hors_sujet"))
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.Created)
            assertThat(resp.bodyAsText()).contains("\"submittedAs\":\"auth\"")
        }

    @Test
    fun `anon plus correctif rejected with 401 before use case is called`() =
        testApplication {
            var called = false
            application {
                install(ContentNegotiation) { json() }
                routing {
                    submitRatingRoute { _: SubmitRatingCommand ->
                        called = true
                        SubmitRatingResult.Accepted(acceptedAnon)
                    }
                }
            }
            val resp =
                client.post("/v1/items/$itemUuid/rating") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody(correctif = "an alternate"))
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(called).isEqualTo(false)
        }

    @Test
    fun `auth duplicate - 409 with existing rating`() =
        testApplication {
            application {
                install(SessionMiddleware) { verifyCookie = { userUuid } }
                install(ContentNegotiation) { json() }
                routing { submitRatingRoute { SubmitRatingResult.AlreadyExists(acceptedAuth) } }
            }
            val resp =
                client.post("/v1/items/$itemUuid/rating") {
                    cookie(SESSION_COOKIE_NAME, "valid-token")
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody())
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.Conflict)
            assertThat(resp.bodyAsText()).contains("\"submittedAs\":\"auth\"")
        }

    @Test
    fun `item missing - 404 problem details`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { submitRatingRoute { SubmitRatingResult.ItemNotFound } }
            }
            val resp =
                client.post("/v1/items/$itemUuid/rating") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody())
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.NotFound)
            assertThat(resp.bodyAsText()).contains("item not found")
        }

    @Test
    fun `correctif filter rejection - 422 with filterId and reason`() =
        testApplication {
            application {
                install(SessionMiddleware) { verifyCookie = { userUuid } }
                install(ContentNegotiation) { json() }
                routing {
                    submitRatingRoute {
                        SubmitRatingResult.CorrectifRejected(filterId = 3, reason = "longueur hors plage")
                    }
                }
            }
            val resp =
                client.post("/v1/items/$itemUuid/rating") {
                    cookie(SESSION_COOKIE_NAME, "valid-token")
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody(correctif = "alt"))
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.UnprocessableEntity)
            assertThat(resp.bodyAsText()).contains("\"filterId\":3")
            assertThat(resp.bodyAsText()).contains("longueur hors plage")
        }

    @Test
    fun `invalid item id path - 400 problem details`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { submitRatingRoute { SubmitRatingResult.Accepted(acceptedAnon) } }
            }
            val resp =
                client.post("/v1/items/not-a-uuid/rating") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody())
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(resp.bodyAsText()).contains("invalid item id")
        }

    @Test
    fun `flag enum lowercase round-trips into FlagReason`() {
        assertThat(FlagReason.valueOf("hors_sujet".uppercase())).isEqualTo(FlagReason.HORS_SUJET)
    }
}
