package com.bliss.identity.api.routes

import com.bliss.identity.api.auth.authenticated
import com.bliss.identity.api.dto.LinkedProviderDto
import com.bliss.identity.api.dto.MeResponse
import com.bliss.identity.application.usecases.GetMeQuery
import com.bliss.identity.application.usecases.GetMeUseCase
import com.bliss.identity.application.usecases.WhoAmIUseCase
import com.bliss.identity.infrastructure.provider.toWire
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.me(
    getMe: GetMeUseCase,
    whoAmI: WhoAmIUseCase,
) {
    get("/v1/users/me") {
        val auth = call.authenticated(whoAmI) ?: return@get
        val result = getMe.execute(GetMeQuery(userId = auth.userId))
        call.respond(
            HttpStatusCode.OK,
            MeResponse(
                id = result.userId.value.toString(),
                displayName = result.displayName.value,
                createdAt = result.createdAt.toString(),
                providers =
                    result.linkedProviders.map { lp ->
                        LinkedProviderDto(
                            provider = lp.provider.toWire(),
                            linkedAt = lp.linkedAt.toString(),
                            emailOptIn = lp.emailOptIn,
                        )
                    },
            ),
        )
    }
}
