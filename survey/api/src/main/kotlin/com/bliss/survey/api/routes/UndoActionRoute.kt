package com.bliss.survey.api.routes

import com.bliss.survey.api.auth.UserIdKey
import com.bliss.survey.api.dto.ProblemDetails
import com.bliss.survey.api.dto.UndoActionRequest
import com.bliss.survey.api.respondProblem
import com.bliss.survey.application.usecases.UndoActionResult
import com.bliss.survey.application.usecases.UndoActionUseCase
import com.bliss.survey.domain.model.UserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

// POST /v1/actions/undo — capability token in the body; auth-optional (ADR-0059).
fun Route.undoActionRoute(undoAction: suspend (token: String, userId: UserId?) -> UndoActionResult) {
    post("/v1/actions/undo") {
        val body = call.receive<UndoActionRequest>()
        val userId = call.attributes.getOrNull(UserIdKey)?.let { UserId(it) }
        when (undoAction(body.token, userId)) {
            UndoActionResult.Undone -> call.respond(HttpStatusCode.NoContent)

            UndoActionResult.NotFound ->
                call.respondProblem(
                    HttpStatusCode.NotFound,
                    ProblemDetails(
                        type = "about:blank",
                        title = "action not found",
                        status = HttpStatusCode.NotFound.value,
                    ),
                )

            UndoActionResult.Expired ->
                call.respondProblem(
                    HttpStatusCode.Gone,
                    ProblemDetails(
                        type = "about:blank",
                        title = "undo window expired",
                        status = HttpStatusCode.Gone.value,
                    ),
                )
        }
    }
}

// concrete overload that takes the use case directly, keeping the lambda seam out of production wiring
fun Route.undoActionRoute(useCase: UndoActionUseCase) = undoActionRoute { token, userId -> useCase.execute(token, userId) }
