// REST routes for the lobby control plane (game/api/openapi.yaml).
//
// POST /v1/lobbies          — create a new lobby
// GET  /v1/lobbies/{lobbyId} — read current lobby state
//
// Wave F · PR #9. The realtime surface (joinLobby / cellUpdate / etc.) ships
// in PR #10's WebSocket route; both PRs add their mounts inside Module.kt's
// `routing { }` block — the additions are independent lines and merge cleanly.
package com.bliss.game.api.routes

import com.bliss.game.api.SessionManager
import com.bliss.game.api.dto.CreateLobbyRequestDto
import com.bliss.game.api.dto.ProblemDetails
import com.bliss.game.api.mapper.toResponseDto
import com.bliss.game.application.ports.LobbyRepository
import com.bliss.game.application.usecases.CreateLobbyUseCase
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.SessionId
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val INVALID_CREATE_TYPE = "https://bliss.example/errors/invalid-lobby-create-request"
private const val INVALID_LOBBY_ID_TYPE = "https://bliss.example/errors/invalid-lobby-id"
private const val LOBBY_NOT_FOUND_TYPE = "https://bliss.example/errors/lobby-not-found"

/**
 * `POST /v1/lobbies` + `GET /v1/lobbies/{lobbyId}`. The route owns DTO ↔
 * domain translation and HTTP status / RFC 7807 mapping. `IllegalArgumentException`s
 * thrown by domain value-class init blocks (Pseudonym, SessionId, LobbyId)
 * are caught locally so the response carries a typed `type` URI; the
 * StatusPages catch-all in Module.kt is the safety net only.
 */
fun Route.lobbies(
    createLobby: CreateLobbyUseCase,
    repo: LobbyRepository,
    sessionManager: SessionManager,
) {
    route("/v1/lobbies") {
        post {
            val request =
                try {
                    call.receive<CreateLobbyRequestDto>()
                } catch (cause: SerializationException) {
                    return@post call.respondInvalidCreate("Request body is not a valid CreateLobbyRequest: ${cause.message}")
                }
            val ownerSessionId =
                runCatching { SessionId(request.ownerSessionId) }
                    .getOrElse { return@post call.respondInvalidCreate(it.message) }
            val ownerPseudonym =
                runCatching { Pseudonym(request.ownerPseudonym) }
                    .getOrElse { return@post call.respondInvalidCreate(it.message) }

            val lobby = createLobby(ownerSessionId, ownerPseudonym).value
            call.response.header(HttpHeaders.Location, "/v1/lobbies/${lobby.id.value}")
            // Newly-created lobby has no live WS sessions yet; presence is empty.
            call.respond(HttpStatusCode.Created, lobby.toResponseDto())
        }

        get("{lobbyId}") {
            val raw = call.parameters["lobbyId"].orEmpty()
            val lobbyId =
                runCatching { LobbyId(raw) }
                    .getOrElse {
                        return@get call.respondProblem(
                            HttpStatusCode.BadRequest,
                            "Invalid lobby id",
                            INVALID_LOBBY_ID_TYPE,
                            "Path parameter lobbyId must be an 8-char base58 nanoid, was '$raw'.",
                        )
                    }

            val lobby = repo.findById(lobbyId)
            if (lobby == null) {
                return@get call.respondProblem(
                    HttpStatusCode.NotFound,
                    "Lobby not found",
                    LOBBY_NOT_FOUND_TYPE,
                    "No lobby exists for id '${lobbyId.value}'.",
                )
            }
            // Mirror the WebSocket snapshot: REST `GET` rehydrating a refreshing
            // client carries the same ephemeral cursor map so the UI can render
            // peer cursors before the WS handshake completes.
            call.respond(HttpStatusCode.OK, lobby.toResponseDto(sessionManager.getPresence(lobbyId)))
        }
    }
}

private suspend fun ApplicationCall.respondInvalidCreate(detail: String?) =
    respondProblem(HttpStatusCode.BadRequest, "Invalid lobby create request", INVALID_CREATE_TYPE, detail)

private suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    title: String,
    type: String,
    detail: String?,
) {
    val problem = ProblemDetails(type, title, status.value, detail, request.local.uri)
    respondText(
        text = Json.encodeToString(ProblemDetails.serializer(), problem),
        contentType = ContentType.parse("application/problem+json"),
        status = status,
    )
}
