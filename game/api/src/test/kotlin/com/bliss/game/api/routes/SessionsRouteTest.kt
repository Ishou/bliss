package com.bliss.game.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.startsWith
import com.bliss.game.api.dto.DeleteSessionResponseDto
import com.bliss.game.api.dto.LobbySummaryDto
import com.bliss.game.application.usecases.EraseSessionUseCase
import com.bliss.game.application.usecases.ListLobbiesForSession
import com.bliss.game.domain.GamePuzzle
import com.bliss.game.domain.GameSession
import com.bliss.game.domain.GridConfig
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyCode
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.LobbyLifecycleState
import com.bliss.game.domain.LobbyTitle
import com.bliss.game.domain.Player
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.SessionId
import com.bliss.game.infrastructure.InMemoryLobbyRepository
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

/**
 * Wire-path tests for `/v1/sessions/{sessionId}/lobbies` via Ktor
 * testApplication. Mirrors LobbiesRouteTest style. ASCII-only test names.
 */
class SessionsRouteTest {
    private val sessionId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"
    private val otherSessionId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c"
    private val baseInstant: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `GET returns 200 with two lobby summaries when the session has two lobbies`() =
        testApplication {
            val repo = InMemoryLobbyRepository()
            val owner = SessionId(sessionId)
            val titled =
                lobby(
                    id = LobbyId.generate(),
                    owner = owner,
                    title = LobbyTitle("Friday night puzzle"),
                    lastActivityAt = baseInstant.plusSeconds(100),
                )
            val untitled =
                lobby(
                    id = LobbyId.generate(),
                    owner = owner,
                    title = null,
                    lastActivityAt = baseInstant.plusSeconds(50),
                )
            repo.save(titled)
            repo.save(untitled)
            setupApp(repo)
            val client = jsonClient()

            val response = client.get("/v1/sessions/$sessionId/lobbies")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers["Content-Type"]!!).startsWith("application/json")
            val body = response.bodyAsText()
            val summaries = JSON.decodeFromString<List<LobbySummaryDto>>(body)
            assertThat(summaries).hasSize(2)
            // ordered by lastActivityAt descending
            assertThat(summaries[0].id).isEqualTo(titled.id.value)
            assertThat(summaries[0].title).isNotNull()
            assertThat(summaries[0].title!!).isEqualTo("Friday night puzzle")
            assertThat(summaries[1].id).isEqualTo(untitled.id.value)
            assertThat(summaries[1].title).isEqualTo(null)
        }

    @Test
    fun `GET returns 200 with empty array when the session has no lobbies`() =
        testApplication {
            val repo = InMemoryLobbyRepository()
            setupApp(repo)
            val client = jsonClient()

            val response = client.get("/v1/sessions/$sessionId/lobbies")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = response.bodyAsText()
            val summaries = JSON.decodeFromString<List<LobbySummaryDto>>(body)
            assertThat(summaries).hasSize(0)
            assertThat(body).contains("[]")
        }

    @Test
    fun `GET with an invalid sessionId returns 400 with RFC 7807 problem json`() =
        testApplication {
            val repo = InMemoryLobbyRepository()
            setupApp(repo)

            val response = client.get("/v1/sessions/not-a-uuid/lobbies")

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.headers["Content-Type"]!!).startsWith("application/problem+json")
            val body = response.bodyAsText()
            assertThat(body).contains("\"status\":400")
            assertThat(body).contains("https://bliss.example/errors/invalid-session-id")
        }

    @Test
    fun `DELETE returns 200 with all-zero counts when the session has no lobbies`() =
        testApplication {
            val repo = InMemoryLobbyRepository()
            setupApp(repo)
            val client = jsonClient()

            val response = client.delete("/v1/sessions/$sessionId")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = JSON.decodeFromString<DeleteSessionResponseDto>(response.bodyAsText())
            assertThat(body.deletedLobbies).isEqualTo(0)
            assertThat(body.transferredLobbies).isEqualTo(0)
            assertThat(body.removedPlayerships).isEqualTo(0)
            assertThat(body.anonymisedEntries).isEqualTo(0)
        }

    @Test
    fun `DELETE returns 200 with deletedLobbies count when the session was a sole-owner`() =
        testApplication {
            val repo = InMemoryLobbyRepository()
            val owner = SessionId(sessionId)
            repo.save(lobby(id = LobbyId.generate(), owner = owner))
            setupApp(repo)
            val client = jsonClient()

            val response = client.delete("/v1/sessions/$sessionId")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = JSON.decodeFromString<DeleteSessionResponseDto>(response.bodyAsText())
            assertThat(body.deletedLobbies).isEqualTo(1)
            assertThat(body.transferredLobbies).isEqualTo(0)
            assertThat(body.removedPlayerships).isEqualTo(0)
        }

    @Test
    fun `DELETE is idempotent - second call returns all-zero counts`() =
        testApplication {
            val repo = InMemoryLobbyRepository()
            val owner = SessionId(sessionId)
            repo.save(lobby(id = LobbyId.generate(), owner = owner))
            setupApp(repo)
            val client = jsonClient()

            val first = client.delete("/v1/sessions/$sessionId")
            assertThat(first.status).isEqualTo(HttpStatusCode.OK)

            val second = client.delete("/v1/sessions/$sessionId")

            assertThat(second.status).isEqualTo(HttpStatusCode.OK)
            val body = JSON.decodeFromString<DeleteSessionResponseDto>(second.bodyAsText())
            assertThat(body.deletedLobbies).isEqualTo(0)
            assertThat(body.transferredLobbies).isEqualTo(0)
            assertThat(body.removedPlayerships).isEqualTo(0)
            assertThat(body.anonymisedEntries).isEqualTo(0)
        }

    @Test
    fun `DELETE with an invalid sessionId returns 400 with RFC 7807 problem json`() =
        testApplication {
            val repo = InMemoryLobbyRepository()
            setupApp(repo)

            val response = client.delete("/v1/sessions/not-a-uuid")

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.headers["Content-Type"]!!).startsWith("application/problem+json")
            val body = response.bodyAsText()
            assertThat(body).contains("\"status\":400")
            assertThat(body).contains("https://bliss.example/errors/invalid-session-id")
        }

    // ADR-0039 amendment 2026-05-12: WAITING lobbies are "salons d'attente",
    // not "parties", and must not appear in the "Mes parties" listing.
    @Test
    fun `GET excludes WAITING lobbies and returns only IN_PROGRESS or COMPLETED`() =
        testApplication {
            val repo = InMemoryLobbyRepository()
            val owner = SessionId(sessionId)
            val waiting =
                lobby(
                    id = LobbyId.generate(),
                    owner = owner,
                    state = LobbyLifecycleState.WAITING,
                    lastActivityAt = baseInstant.plusSeconds(10),
                )
            val inProgress =
                lobby(
                    id = LobbyId.generate(),
                    owner = owner,
                    state = LobbyLifecycleState.IN_PROGRESS,
                    lastActivityAt = baseInstant.plusSeconds(20),
                )
            repo.save(waiting)
            repo.save(inProgress)
            setupApp(repo)
            val client = jsonClient()

            val response = client.get("/v1/sessions/$sessionId/lobbies")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val summaries = JSON.decodeFromString<List<LobbySummaryDto>>(response.bodyAsText())
            assertThat(summaries).hasSize(1)
            assertThat(summaries[0].id).isEqualTo(inProgress.id.value)
        }

    @Test
    fun `GET filters out lobbies the session is not a member of`() =
        testApplication {
            val repo = InMemoryLobbyRepository()
            val owner = SessionId(sessionId)
            val other = SessionId(otherSessionId)
            val mine = lobby(id = LobbyId.generate(), owner = owner)
            val theirs = lobby(id = LobbyId.generate(), owner = other)
            repo.save(mine)
            repo.save(theirs)
            setupApp(repo)
            val client = jsonClient()

            val response = client.get("/v1/sessions/$sessionId/lobbies")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val summaries = JSON.decodeFromString<List<LobbySummaryDto>>(response.bodyAsText())
            assertThat(summaries).hasSize(1)
            assertThat(summaries[0].id).isEqualTo(mine.id.value)
        }

    private fun ApplicationTestBuilder.setupApp(repo: InMemoryLobbyRepository) {
        application {
            install(ServerContentNegotiation) { json(JSON) }
            routing {
                sessions(ListLobbiesForSession(repo), EraseSessionUseCase(repo))
            }
        }
    }

    // Defaults to IN_PROGRESS so the helper produces lobbies that the GET
    // listing returns. Per the ADR-0039 amendment of 2026-05-12, WAITING
    // lobbies are excluded from the "Mes parties" surface; tests that
    // need to cover that explicitly pass `state = WAITING`. IN_PROGRESS
    // and COMPLETED carry a GameSession because the Lobby invariant
    // requires one (see Lobby.kt:112).
    private fun lobby(
        id: LobbyId,
        owner: SessionId,
        title: LobbyTitle? = null,
        state: LobbyLifecycleState = LobbyLifecycleState.IN_PROGRESS,
        lastActivityAt: Instant = baseInstant,
    ): Lobby {
        val players = mapOf(owner to Player(owner, Pseudonym("Alice"), baseInstant))
        val game: GameSession? =
            when (state) {
                LobbyLifecycleState.WAITING -> null
                LobbyLifecycleState.IN_PROGRESS ->
                    GameSession(puzzle(), emptyMap(), baseInstant, null)
                LobbyLifecycleState.COMPLETED ->
                    GameSession(puzzle(), emptyMap(), baseInstant, baseInstant.plusSeconds(60))
            }
        return Lobby(
            id = id,
            ownerSessionId = owner,
            players = players,
            state = state,
            gridConfig = GridConfig(15, 12),
            game = game,
            lastActivityAt = lastActivityAt,
            code = LobbyCode.generate(),
            title = title,
        )
    }

    private fun puzzle(): GamePuzzle =
        GamePuzzle(
            id = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c"),
            title = "Petite grille",
            language = "fr",
            width = 5,
            height = 5,
            cells = emptyList(),
            clues = emptyList(),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json(JSON) } }

    private companion object {
        private val JSON: Json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
    }
}
