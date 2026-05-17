package com.bliss.identity.api.routes

import com.bliss.identity.api.auth.authenticated
import com.bliss.identity.api.dto.WhoAmIResponse
import com.bliss.identity.application.usecases.WhoAmIUseCase
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.whoAmI(whoAmI: WhoAmIUseCase) {
    get("/v1/auth/whoami") {
        val result = call.authenticated(whoAmI) ?: return@get
        call.respond(
            HttpStatusCode.OK,
            WhoAmIResponse(
                userId = result.userId.value.toString(),
                displayName = result.displayName.value,
            ),
        )
    }
}
