package com.bliss.identity.api.routes

import com.bliss.identity.api.auth.SessionCookies
import com.bliss.identity.api.auth.authenticated
import com.bliss.identity.application.usecases.LogoutCommand
import com.bliss.identity.application.usecases.LogoutUseCase
import com.bliss.identity.application.usecases.WhoAmIUseCase
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

// WhoAmIResult does not expose sessionId; the cookie value (a UUID) is the session id by design.
fun Route.logout(
    logout: LogoutUseCase,
    whoAmI: WhoAmIUseCase,
) {
    post("/v1/auth/logout") {
        call.authenticated(whoAmI) ?: return@post
        val sessionId = SessionCookies.read(call.request)!!
        logout.execute(LogoutCommand(sessionId))
        SessionCookies.clear(call)
        call.respond(HttpStatusCode.NoContent)
    }
}
