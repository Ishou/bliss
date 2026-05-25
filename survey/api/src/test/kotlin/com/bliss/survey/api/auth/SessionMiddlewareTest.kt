package com.bliss.survey.api.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.util.UUID

class SessionMiddlewareTest {
    @Test
    fun `no cookie - userId attribute is null and request proceeds`() =
        testApplication {
            application {
                install(SessionMiddleware) {
                    verifyCookie = { null }
                }
                routing {
                    get("/probe") {
                        val u = call.attributes.getOrNull(UserIdKey)
                        call.respondText(text = u?.toString() ?: "anon", status = HttpStatusCode.OK)
                    }
                }
            }
            val resp = client.get("/probe")
            assertThat(resp.status).isEqualTo(HttpStatusCode.OK)
            assertThat(resp.bodyAsText()).isEqualTo("anon")
        }

    @Test
    fun `valid cookie - userId attribute is set`() =
        testApplication {
            val fixedUser = UUID.fromString("01234567-89ab-7cde-89ab-0123456789ab")
            application {
                install(SessionMiddleware) {
                    verifyCookie = { c -> if (c == "valid-token") fixedUser else null }
                }
                routing {
                    get("/probe") {
                        val u = call.attributes.getOrNull(UserIdKey)
                        call.respondText(text = u?.toString() ?: "anon", status = HttpStatusCode.OK)
                    }
                }
            }
            val resp = client.get("/probe") { cookie(SESSION_COOKIE_NAME, "valid-token") }
            assertThat(resp.status).isEqualTo(HttpStatusCode.OK)
            assertThat(resp.bodyAsText()).isEqualTo(fixedUser.toString())
        }

    @Test
    fun `invalid cookie - userId attribute null (no 401)`() =
        testApplication {
            application {
                install(SessionMiddleware) {
                    verifyCookie = { null }
                }
                routing {
                    get("/probe") {
                        val u = call.attributes.getOrNull(UserIdKey)
                        call.respondText(text = u?.toString() ?: "anon", status = HttpStatusCode.OK)
                    }
                }
            }
            val resp = client.get("/probe") { cookie(SESSION_COOKIE_NAME, "tampered") }
            assertThat(resp.status).isEqualTo(HttpStatusCode.OK)
            assertThat(resp.bodyAsText()).isEqualTo("anon")
        }
}
