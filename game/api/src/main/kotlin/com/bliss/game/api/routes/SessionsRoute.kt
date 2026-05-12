// GET /v1/sessions/{sessionId}/lobbies — list a session's lobbies for the
// "My games" surface (ADR-0039). Validates the path param via the
// SessionId value class (UUID v7 enforced in init). RFC 7807 problem
// envelope on validation errors. Always 200 + array (never 404 — empty
// array is the no-lobbies answer per game/api/openapi.yaml).
//
// DELETE /v1/sessions/{sessionId} — RGPD Article 17 erasure (ADR-0039,
// PR #11 of the multiplayer persistence rollout). Three-rule cascade
// applied per lobby; always idempotent, always returns 200 with the
// per-rule counts (zero is a valid success).
package com.bliss.game.api.routes

import com.bliss.game.api.dto.DeleteSessionResponseDto
import com.bliss.game.api.dto.ProblemDetails
import com.bliss.game.api.mapper.toDto
import com.bliss.game.application.usecases.EraseSessionUseCase
import com.bliss.game.application.usecases.ListLobbiesForSession
import com.bliss.game.domain.SessionId
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json

private const val INVALID_SESSION_ID_TYPE = "https://bliss.example/errors/invalid-session-id"

fun Route.sessions(
    listLobbies: ListLobbiesForSession,
    erase: EraseSessionUseCase,
) {
    route("/v1/sessions") {
        get("{sessionId}/lobbies") {
            val raw = call.parameters["sessionId"].orEmpty()
            val sessionId =
                runCatching { SessionId(raw) }.getOrNull()
                    ?: return@get call.respondSessionProblem(
                        HttpStatusCode.BadRequest,
                        "sessionId invalide",
                        INVALID_SESSION_ID_TYPE,
                        "sessionId doit être un UUID v7",
                    )
            val summaries = listLobbies(sessionId)
            call.respond(summaries.map { it.toDto() })
        }
        delete("{sessionId}") {
            val raw = call.parameters["sessionId"].orEmpty()
            val sessionId =
                runCatching { SessionId(raw) }.getOrNull()
                    ?: return@delete call.respondSessionProblem(
                        HttpStatusCode.BadRequest,
                        "sessionId invalide",
                        INVALID_SESSION_ID_TYPE,
                        "sessionId doit être un UUID v7",
                    )
            val result = erase(sessionId)
            call.respond(
                DeleteSessionResponseDto(
                    deletedLobbies = result.deletedLobbies,
                    transferredLobbies = result.transferredLobbies,
                    removedPlayerships = result.removedPlayerships,
                    anonymisedEntries = result.anonymisedEntries,
                ),
            )
        }
    }
}

private suspend fun ApplicationCall.respondSessionProblem(
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
