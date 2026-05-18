// Cookie-authed lobby rebind/unbind. Phase 6c: anon→authed transition links
// existing lobby seats to the signed-in user; the inverse runs before sign-out.
//
// POST /v1/lobbies/players/rebind  — sessionCookie auth
// POST /v1/lobbies/players/unbind  — sessionCookie auth
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

/**
 * Mounts both endpoints. The route never throws — `verify` returns null on
 * cookie absence / 401 / unreachable identity-api, so the handler answers 401.
 * Domain value-class `IllegalArgumentException`s (malformed session id or
 * pseudonym) fall through to the StatusPages handler in [com.bliss.game.api.module].
 */
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
