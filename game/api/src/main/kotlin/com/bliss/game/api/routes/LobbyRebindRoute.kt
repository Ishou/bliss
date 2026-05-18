package com.bliss.game.api.routes

import com.bliss.game.api.auth.CookieNames
import com.bliss.game.application.auth.CookieVerifier
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

/** Mounts rebind/unbind; verify() returns null on missing/invalid cookie so routes respond 401. */
fun Route.lobbyRebind(
    verifier: CookieVerifier,
    lobbies: LobbyRepository,
) {
    post("/v1/lobbies/players/rebind") {
        val rawCookie = call.request.cookies[CookieNames.SESSION]
        val whoAmI = verifier.verify(rawCookie)
        if (whoAmI == null) {
            return@post call.respond(HttpStatusCode.Unauthorized)
        }
        val req = call.receive<RebindRequest>()
        val anonSessionId = SessionId(req.anonSessionId)
        lobbies.rebindAnonSeats(
            anonSessionId = anonSessionId,
            userId = whoAmI.userId,
            newPseudonym = whoAmI.displayName,
        )
        call.respond(HttpStatusCode.NoContent)
    }

    post("/v1/lobbies/players/unbind") {
        val rawCookie = call.request.cookies[CookieNames.SESSION]
        val whoAmI = verifier.verify(rawCookie)
        if (whoAmI == null) {
            return@post call.respond(HttpStatusCode.Unauthorized)
        }
        val req = call.receive<UnbindRequest>()
        lobbies.unbindUserSeats(
            userId = whoAmI.userId,
            anonPseudonym = Pseudonym.of(req.anonPseudonym),
        )
        call.respond(HttpStatusCode.NoContent)
    }
}
