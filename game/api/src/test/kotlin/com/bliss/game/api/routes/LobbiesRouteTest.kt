package com.bliss.game.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.matches
import assertk.assertions.startsWith
import com.bliss.game.api.SessionManager
import com.bliss.game.api.dto.CreateLobbyRequestDto
import com.bliss.game.api.dto.LobbyResponseDto
import com.bliss.game.api.module
import com.bliss.game.application.auth.CookieVerifier
import com.bliss.game.application.auth.WhoAmI
import com.bliss.game.application.ports.Clock
import com.bliss.game.application.usecases.CreateLobbyUseCase
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.UserId
import com.bliss.game.infrastructure.InMemoryLobbyRepository
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.time.Instant
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

/**
 * Wire-path tests for `/v1/lobbies` via Ktor [testApplication]. Asserts the
 * full HTTP envelope (status, Content-Type, headers, body shape) and a
 * round-trip through [LobbyResponseDto] as a schema-drift guard.
 *
 * Test names use ASCII hyphens only — non-ASCII (em-dash, etc.) crashes
 * compileTestKotlin under POSIX-locale CI runners because the class-file
 * path becomes unmappable.
 */
class LobbiesRouteTest {
    private val ownerSessionId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"
    private val ownerPseudonym = "Joueur 1234"

    @Test
    fun `POST creates a lobby, returns 201 with Location header and a body matching LobbyResponseDto`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val response = client.post("/v1/lobbies") { jsonBody(CreateLobbyRequestDto(ownerSessionId, ownerPseudonym)) }

            assertThat(response.status).isEqualTo(HttpStatusCode.Created)
            assertThat(response.headers["Content-Type"]!!).startsWith("application/json")
            val location = response.headers[HttpHeaders.Location]
            assertThat(location).isNotNull()
            assertThat(location!!).matches(Regex("^/v1/lobbies/[1-9A-HJ-NP-Za-km-z]{8}$"))

            val body = response.bodyAsText()
            val lobby = JSON.decodeFromString<LobbyResponseDto>(body)
            assertThat(lobby.id).matches(Regex("^[1-9A-HJ-NP-Za-km-z]{8}$"))
            assertThat(lobby.ownerSessionId).isEqualTo(ownerSessionId)
            assertThat(lobby.players.size).isEqualTo(1)
            assertThat(lobby.players[0].sessionId).isEqualTo(ownerSessionId)
            assertThat(lobby.players[0].pseudonym).isEqualTo(ownerPseudonym)
            assertThat(lobby.state).isEqualTo("WAITING")
            assertThat(lobby.gridConfig.width).isEqualTo(15)
            assertThat(lobby.gridConfig.height).isEqualTo(12)
            // game is null while WAITING; ADR-0003 §6 requires the field to
            // be on the wire (absence and null are distinct). Inspect raw
            // JSON to verify the field was emitted, not omitted.
            val json = Json.parseToJsonElement(body) as JsonObject
            assertThat(json.containsKey("game")).isEqualTo(true)
            // `code` is now minted at lobby creation — pattern-matches the
            // `^[A-HJKM-NP-Z2-9]{6}$` Crockford-style alphabet declared in the
            // openapi schema. Phase 2 of the join-by-code wave.
            assertThat(json.containsKey("code")).isEqualTo(true)
            val code = lobby.code
            assertThat(code).isNotNull()
            assertThat(code!!).matches(Regex("^[A-HJKM-NP-Z2-9]{6}$"))
        }

    @Test
    fun `GET on the just-created lobby returns 200 with the same body shape`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val created = client.post("/v1/lobbies") { jsonBody(CreateLobbyRequestDto(ownerSessionId, ownerPseudonym)) }
            val lobbyId = JSON.decodeFromString<LobbyResponseDto>(created.bodyAsText()).id

            val response = client.get("/v1/lobbies/$lobbyId")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers["Content-Type"]!!).startsWith("application/json")
            val lobby = JSON.decodeFromString<LobbyResponseDto>(response.bodyAsText())
            assertThat(lobby.id).isEqualTo(lobbyId)
            assertThat(lobby.ownerSessionId).isEqualTo(ownerSessionId)
            assertThat(lobby.state).isEqualTo("WAITING")
        }

    @Test
    fun `GET on a non-existent lobbyId returns 404 with RFC 7807 problem json`() =
        testApplication {
            application { module() }

            // Valid base58 nanoid pattern but no lobby was created with this id.
            val response = client.get("/v1/lobbies/abcdefgh")

            assertProblem(response, HttpStatusCode.NotFound, "https://bliss.example/errors/lobby-not-found")
        }

    @Test
    fun `POST with an invalid request body returns 400 with RFC 7807 problem json`() =
        testApplication {
            application { module() }
            val client = jsonClient()
            val invalidCreateUri = "https://bliss.example/errors/invalid-lobby-create-request"

            // Empty pseudonym fails Pseudonym init.
            val emptyPseudonym = client.post("/v1/lobbies") { jsonBody(CreateLobbyRequestDto(ownerSessionId, "")) }
            assertProblem(emptyPseudonym, HttpStatusCode.BadRequest, invalidCreateUri)

            // Non-UUID sessionId fails SessionId init.
            val badSession = client.post("/v1/lobbies") { jsonBody(CreateLobbyRequestDto("not-a-uuid", ownerPseudonym)) }
            assertProblem(badSession, HttpStatusCode.BadRequest, invalidCreateUri)
        }

    @Test
    fun `GET with an invalid lobbyId shape returns 400 with RFC 7807 problem json`() =
        testApplication {
            application { module() }

            // '!' is not in the base58 alphabet. The route's LobbyId(...)
            // value-class init throws IllegalArgumentException; the route
            // catches it and emits a typed problem document.
            val response = client.get("/v1/lobbies/not-bs!")

            assertProblem(response, HttpStatusCode.BadRequest, "https://bliss.example/errors/invalid-lobby-id")
        }

    @Test
    fun `GET by-code returns 200 with the same lobby as the create response`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val created = client.post("/v1/lobbies") { jsonBody(CreateLobbyRequestDto(ownerSessionId, ownerPseudonym)) }
            val createdLobby = JSON.decodeFromString<LobbyResponseDto>(created.bodyAsText())
            val code = createdLobby.code!!

            val response = client.get("/v1/lobbies/by-code/$code")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val resolved = JSON.decodeFromString<LobbyResponseDto>(response.bodyAsText())
            assertThat(resolved.id).isEqualTo(createdLobby.id)
            assertThat(resolved.code).isEqualTo(code)
        }

    @Test
    fun `GET by-code with an unknown but well-formed code returns 404`() =
        testApplication {
            application { module() }

            // Pattern-valid but no lobby was minted with this code.
            val response = client.get("/v1/lobbies/by-code/A2B3C4")

            assertProblem(response, HttpStatusCode.NotFound, "https://bliss.example/errors/lobby-not-found")
        }

    @Test
    fun `GET by-code with a malformed code returns 400`() =
        testApplication {
            application { module() }

            // Lower-case 'a' is outside the Crockford alphabet; LobbyCode
            // init rejects it and the route emits a typed problem.
            val response = client.get("/v1/lobbies/by-code/a2b3c4")

            assertProblem(response, HttpStatusCode.BadRequest, "https://bliss.example/errors/invalid-lobby-code")
        }

    @Test
    fun `POST with a valid session cookie uses the cookie pseudonym and userId, ignoring the request body pseudonym`() =
        testApplicationWithVerifier(
            verifier =
                stubVerifier(
                    WhoAmI(
                        userId = UserId("11111111-1111-1111-1111-111111111111"),
                        displayName = Pseudonym("Isho"),
                    ),
                ),
        ) { client, repo ->
            // Anon pseudonym in the body would normally land on the player row.
            // With a valid cookie, the verifier wins: the player row should
            // carry whoami.displayName + whoami.userId.
            val response =
                client.post("/v1/lobbies") {
                    cookie(name = "__Secure-ws_session", value = "stub-cookie")
                    jsonBody(CreateLobbyRequestDto(ownerSessionId, "Marmotte"))
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.Created)
            val lobby = JSON.decodeFromString<LobbyResponseDto>(response.bodyAsText())
            assertThat(lobby.players[0].pseudonym).isEqualTo("Isho")
            // Domain-level invariant: the persisted owner Player carries the verified userId.
            val saved =
                repo.findById(
                    com.bliss.game.domain
                        .LobbyId(lobby.id),
                )!!
            val owner =
                saved.players[
                    com.bliss.game.domain
                        .SessionId(ownerSessionId),
                ]!!
            assertThat(owner.userId).isEqualTo(UserId("11111111-1111-1111-1111-111111111111"))
            assertThat(owner.pseudonym).isEqualTo(Pseudonym("Isho"))
        }

    @Test
    fun `POST with no cookie keeps anon pseudonym from the request body and leaves userId null`() =
        testApplicationWithVerifier(verifier = stubVerifier(null)) { client, repo ->
            val response =
                client.post("/v1/lobbies") {
                    jsonBody(CreateLobbyRequestDto(ownerSessionId, "Marmotte"))
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.Created)
            val lobby = JSON.decodeFromString<LobbyResponseDto>(response.bodyAsText())
            assertThat(lobby.players[0].pseudonym).isEqualTo("Marmotte")
            val saved =
                repo.findById(
                    com.bliss.game.domain
                        .LobbyId(lobby.id),
                )!!
            val owner =
                saved.players[
                    com.bliss.game.domain
                        .SessionId(ownerSessionId),
                ]!!
            assertThat(owner.userId).isEqualTo(null)
            assertThat(owner.pseudonym).isEqualTo(Pseudonym("Marmotte"))
        }

    private fun stubVerifier(returning: WhoAmI?): CookieVerifier =
        object : CookieVerifier {
            override suspend fun verify(rawCookieValue: String?): WhoAmI? = returning
        }

    private fun testApplicationWithVerifier(
        verifier: CookieVerifier,
        block: suspend (io.ktor.client.HttpClient, InMemoryLobbyRepository) -> Unit,
    ) = testApplication {
        val repo = InMemoryLobbyRepository()
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.parse("2026-05-18T12:00:00Z")
            }
        val createLobby = CreateLobbyUseCase(repo, clock)
        val sessionManager = SessionManager()
        application {
            install(ServerContentNegotiation) { json(JSON) }
            routing {
                lobbies(
                    createLobby = createLobby,
                    repo = repo,
                    sessionManager = sessionManager,
                    cookieVerifier = verifier,
                )
            }
        }
        val client = createClient { install(ContentNegotiation) { json(JSON) } }
        block(client, repo)
    }

    private suspend fun assertProblem(
        response: io.ktor.client.statement.HttpResponse,
        expectedStatus: HttpStatusCode,
        expectedTypeUri: String,
    ) {
        assertThat(response.status).isEqualTo(expectedStatus)
        assertThat(response.headers["Content-Type"]!!).startsWith("application/problem+json")
        val body = response.bodyAsText()
        assertThat(body).contains("\"status\":${expectedStatus.value}")
        assertThat(body).contains(expectedTypeUri)
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json(JSON) } }

    private companion object {
        // Match the server-side Json: ignore unknown keys (forward-compat),
        // skip explicit nulls when serializing requests.
        private val JSON: Json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(payload: CreateLobbyRequestDto) {
    contentType(ContentType.Application.Json)
    setBody(payload)
}
