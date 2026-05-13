package com.bliss.grid.api.routes

import com.bliss.grid.api.dto.ProblemDetails
import com.bliss.grid.application.puzzle.DeleteSessionUseCase
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

private const val INVALID_SESSION_ID_TYPE_DELETE: String =
    "https://bliss.example/errors/invalid-session-id"

@Serializable
internal data class DeleteSessionResponse(
    val deleted: Int,
)

/**
 * `DELETE /v1/sessions/{sessionId}` — RGPD Article 17 erasure (ADR-0025 §5).
 *
 * Removes every server-side row keyed by the given session id (today: only
 * `puzzle_hint_usage`). The frontend's "Effacer mes données" button calls
 * this and then clears the local browser state (`sessionId` + pseudonym).
 *
 * Idempotent: a sessionId that has nothing to delete returns 200 with
 * `deleted = 0`. Always 200 (never 404) — disclosing whether a sessionId
 * had data would itself be a privacy leak.
 *
 * Matomo visit erasure is intentionally not invoked here. The
 * daily-rotated salted hash makes prior-day visits already non-attributable,
 * and a fresh local sessionId after the call breaks linkage going forward.
 * The privacy notice discloses this trade-off.
 */
fun Route.deleteSession(deleteSession: DeleteSessionUseCase) {
    delete("/v1/sessions/{sessionId}") {
        val rawId = call.parameters["sessionId"].orEmpty()
        val sessionId =
            try {
                UUID.fromString(rawId)
            } catch (_: IllegalArgumentException) {
                val problem =
                    ProblemDetails(
                        type = INVALID_SESSION_ID_TYPE_DELETE,
                        title = "Identifiant de session invalide",
                        status = HttpStatusCode.BadRequest.value,
                        detail = "Le paramètre sessionId doit être un UUID, reçu : '$rawId'.",
                        instance = call.request.local.uri,
                    )
                call.respondText(
                    text = Json.encodeToString(ProblemDetails.serializer(), problem),
                    contentType = ContentType.parse("application/problem+json"),
                    status = HttpStatusCode.BadRequest,
                )
                return@delete
            }
        val deleted = withContext(Dispatchers.IO) { deleteSession.execute(sessionId) }
        call.respond(HttpStatusCode.OK, DeleteSessionResponse(deleted = deleted))
    }
}
