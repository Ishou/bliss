package com.bliss.survey.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.bliss.survey.api.WIRE_JSON
import com.bliss.survey.api.auth.SESSION_COOKIE_NAME
import com.bliss.survey.api.auth.SessionMiddleware
import com.bliss.survey.application.usecases.UndoActionResult
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
import java.util.UUID

class UndoActionRouteTest {
    private val userUuid = UUID.fromString("33333333-3333-7333-8333-333333333333")

    @Test
    fun `undone - 204 no content`() =
        testApplication {
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing { undoActionRoute { _, _ -> UndoActionResult.Undone } }
            }
            val resp =
                client.post("/v1/actions/undo") {
                    contentType(ContentType.Application.Json)
                    setBody("{\"token\":\"undo-tok\"}")
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.NoContent)
        }

    @Test
    fun `not found - 404 problem details`() =
        testApplication {
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing { undoActionRoute { _, _ -> UndoActionResult.NotFound } }
            }
            val resp =
                client.post("/v1/actions/undo") {
                    contentType(ContentType.Application.Json)
                    setBody("{\"token\":\"unknown\"}")
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.NotFound)
            assertThat(resp.bodyAsText()).contains("action not found")
        }

    @Test
    fun `expired - 410 gone`() =
        testApplication {
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing { undoActionRoute { _, _ -> UndoActionResult.Expired } }
            }
            val resp =
                client.post("/v1/actions/undo") {
                    contentType(ContentType.Application.Json)
                    setBody("{\"token\":\"stale-tok\"}")
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.Gone)
            assertThat(resp.bodyAsText()).contains("undo window expired")
        }

    @Test
    fun `token read from body not path`() =
        testApplication {
            var seenToken: String? = null
            application {
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing {
                    undoActionRoute { token, _ ->
                        seenToken = token
                        UndoActionResult.Undone
                    }
                }
            }
            client.post("/v1/actions/undo") {
                contentType(ContentType.Application.Json)
                setBody("{\"token\":\"body-token\"}")
            }
            assertThat(seenToken).isEqualTo("body-token")
        }

    @Test
    fun `session user id forwarded to use case`() =
        testApplication {
            var seenUser: UserId? = null
            application {
                install(SessionMiddleware) { verifyCookie = { userUuid } }
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing {
                    undoActionRoute { _, userId ->
                        seenUser = userId
                        UndoActionResult.Undone
                    }
                }
            }
            client.post("/v1/actions/undo") {
                cookie(SESSION_COOKIE_NAME, "valid-token")
                contentType(ContentType.Application.Json)
                setBody("{\"token\":\"body-token\"}")
            }
            assertThat(seenUser).isEqualTo(UserId(userUuid))
        }
}
