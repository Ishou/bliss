package com.bliss.game.api.routes

import com.bliss.game.api.auth.CookieNames
import com.bliss.game.application.auth.CookieVerifier
import com.bliss.game.application.lobby.LobbyWriteCoordinator
import com.bliss.game.application.ports.LobbyRepository
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.SessionId
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class RebindRequest(
    val anonSessionId: String,
)

@Serializable
data class UnbindRequest(
    val anonPseudonym: String,
)

/** Mounts rebind/unbind; cookie cached verify picks the lock key, fresh verify under the lock guards against stale-cache writes. */
fun Route.lobbyRebind(
    verifier: CookieVerifier,
    lobbies: LobbyRepository,
    coordinator: LobbyWriteCoordinator,
) {
    post("/v1/lobbies/players/rebind") {
        val rawCookie = call.request.cookies[CookieNames.SESSION]
        val cached = verifier.verify(rawCookie)
        if (cached == null) {
            return@post call.respond(HttpStatusCode.Unauthorized)
        }
        val req = call.receive<RebindRequest>()
        val anonSessionId = SessionId(req.anonSessionId)
        val ok =
            coordinator.withUserLock(cached.userId) { conn ->
                val fresh = verifier.verifyFresh(rawCookie)
                if (fresh == null || fresh.userId != cached.userId) {
                    false
                } else {
                    lobbies.rebindAnonSeats(
                        conn = conn,
                        anonSessionId = anonSessionId,
                        userId = fresh.userId,
                        newPseudonym = fresh.displayName,
                    )
                    true
                }
            }
        if (!ok) {
            return@post call.respond(HttpStatusCode.Unauthorized)
        }
        call.respond(HttpStatusCode.NoContent)
    }

    post("/v1/lobbies/players/unbind") {
        val rawCookie = call.request.cookies[CookieNames.SESSION]
        val cached = verifier.verify(rawCookie)
        if (cached == null) {
            return@post call.respond(HttpStatusCode.Unauthorized)
        }
        val req = call.receive<UnbindRequest>()
        val anonPseudonym = Pseudonym.of(req.anonPseudonym)
        val ok =
            coordinator.withUserLock(cached.userId) { conn ->
                val fresh = verifier.verifyFresh(rawCookie)
                if (fresh == null || fresh.userId != cached.userId) {
                    false
                } else {
                    lobbies.unbindUserSeats(
                        conn = conn,
                        userId = fresh.userId,
                        anonPseudonym = anonPseudonym,
                    )
                    true
                }
            }
        if (!ok) {
            return@post call.respond(HttpStatusCode.Unauthorized)
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
