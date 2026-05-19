package com.bliss.game.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import com.bliss.game.api.auth.CookieNames
import com.bliss.game.api.dto.ProblemDetails
import com.bliss.game.application.auth.CookieVerifier
import com.bliss.game.application.auth.WhoAmI
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.UserId
import com.bliss.game.infrastructure.InMemoryLobbyRepository
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class LobbyRebindRouteTest {
    private val anonSessionId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"
    private val userId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c"
    private val whoAmI = WhoAmI(UserId(userId), Pseudonym.of("Marmotte 900"))

    @Test
    fun `rebind without Cookie header returns 401`() =
        testApplication {
            setupApp(AlwaysNullVerifier)

            val response =
                client.post("/v1/lobbies/players/rebind") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"anonSessionId":"$anonSessionId"}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        }

    @Test
    fun `rebind with valid cookie and valid body returns 204`() =
        testApplication {
            setupApp(AlwaysValidVerifier(whoAmI))

            val response =
                client.post("/v1/lobbies/players/rebind") {
                    header(HttpHeaders.Cookie, "${CookieNames.SESSION}=valid-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"anonSessionId":"$anonSessionId"}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.NoContent)
        }

    @Test
    fun `unbind without Cookie header returns 401`() =
        testApplication {
            setupApp(AlwaysNullVerifier)

            val response =
                client.post("/v1/lobbies/players/unbind") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"anonPseudonym":"Anon 1234"}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        }

    @Test
    fun `unbind with valid cookie and valid body returns 204`() =
        testApplication {
            setupApp(AlwaysValidVerifier(whoAmI))

            val response =
                client.post("/v1/lobbies/players/unbind") {
                    header(HttpHeaders.Cookie, "${CookieNames.SESSION}=valid-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"anonPseudonym":"Anon 1234"}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.NoContent)
        }

    @Test
    fun `rebind with valid cookie but invalid anonSessionId returns 400`() =
        testApplication {
            setupApp(AlwaysValidVerifier(whoAmI))

            val response =
                client.post("/v1/lobbies/players/rebind") {
                    header(HttpHeaders.Cookie, "${CookieNames.SESSION}=valid-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"anonSessionId":"not-a-uuid"}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.headers["Content-Type"]!!).startsWith("application/problem+json")
            assertThat(response.bodyAsText()).contains("\"status\":400")
        }

    @Test
    fun `unbind with valid cookie but empty anonPseudonym returns 400`() =
        testApplication {
            setupApp(AlwaysValidVerifier(whoAmI))

            val response =
                client.post("/v1/lobbies/players/unbind") {
                    header(HttpHeaders.Cookie, "${CookieNames.SESSION}=valid-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"anonPseudonym":""}""")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.headers["Content-Type"]!!).startsWith("application/problem+json")
            assertThat(response.bodyAsText()).contains("\"status\":400")
        }

    private fun ApplicationTestBuilder.setupApp(
        verifier: CookieVerifier,
        repo: InMemoryLobbyRepository = InMemoryLobbyRepository(),
    ) {
        application {
            install(ServerContentNegotiation) { json(JSON) }
            install(StatusPages) {
                exception<IllegalArgumentException> { call, cause ->
                    call.respondText(
                        text =
                            Json.encodeToString(
                                ProblemDetails.serializer(),
                                ProblemDetails(
                                    type = "about:blank",
                                    title = "Requête invalide",
                                    status = HttpStatusCode.BadRequest.value,
                                    detail = cause.message,
                                    instance = call.request.local.uri,
                                ),
                            ),
                        contentType = ContentType.parse("application/problem+json"),
                        status = HttpStatusCode.BadRequest,
                    )
                }
            }
            routing {
                lobbyRebind(verifier, repo)
            }
        }
    }

    private companion object {
        private val JSON: Json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
    }

    private object AlwaysNullVerifier : CookieVerifier {
        override suspend fun verify(rawCookieValue: String?): WhoAmI? = null

        override suspend fun verifyFresh(rawCookieValue: String?): WhoAmI? = null
    }

    private class AlwaysValidVerifier(
        private val result: WhoAmI,
    ) : CookieVerifier {
        override suspend fun verify(rawCookieValue: String?): WhoAmI? = result

        override suspend fun verifyFresh(rawCookieValue: String?): WhoAmI? = result
    }
}
