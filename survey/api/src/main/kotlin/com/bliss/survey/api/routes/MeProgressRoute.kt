package com.bliss.survey.api.routes

import com.bliss.survey.api.auth.UserIdKey
import com.bliss.survey.api.dto.ProblemDetails
import com.bliss.survey.api.dto.ProgressResponse
import com.bliss.survey.api.respondProblem
import com.bliss.survey.application.ports.UserProgressRepository
import com.bliss.survey.domain.model.UserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// GET /v1/me/progress — auth-required (ADR-0056 §5).
fun Route.meProgressRoute(progress: UserProgressRepository) {
    get("/v1/me/progress") {
        val userId =
            call.attributes.getOrNull(UserIdKey)
                ?: return@get call.respondProblem(
                    HttpStatusCode.Unauthorized,
                    ProblemDetails(
                        type = "about:blank",
                        title = "sign-in required",
                        status = HttpStatusCode.Unauthorized.value,
                    ),
                )
        val state = progress.get(UserId(userId))
        call.respond(
            HttpStatusCode.OK,
            ProgressResponse(
                itemsRated = state?.itemsRated ?: 0,
                calibrationAgreement = state?.calibrationAgreement,
                lastRatedAt = state?.lastRatedAt?.toString(),
            ),
        )
    }
}
