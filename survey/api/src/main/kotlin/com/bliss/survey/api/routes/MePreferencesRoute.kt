package com.bliss.survey.api.routes

import com.bliss.survey.api.auth.UserIdKey
import com.bliss.survey.api.dto.PreferencesPatch
import com.bliss.survey.api.dto.ProblemDetails
import com.bliss.survey.application.ports.ProposedByRepository
import com.bliss.survey.domain.model.UserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.patch

// PATCH /v1/me/preferences — auth-required opt-out toggle on rater-proposed items.
fun Route.mePreferencesRoute(proposedBy: ProposedByRepository) {
    patch("/v1/me/preferences") {
        val userId =
            call.attributes.getOrNull(UserIdKey)
                ?: return@patch call.respond(
                    HttpStatusCode.Unauthorized,
                    ProblemDetails(
                        type = "about:blank",
                        title = "sign-in required",
                        status = HttpStatusCode.Unauthorized.value,
                    ),
                )
        val body = call.receive<PreferencesPatch>()
        proposedBy.setOptOut(UserId(userId), body.deleteProposedOnErasure)
        call.respond(HttpStatusCode.NoContent)
    }
}
