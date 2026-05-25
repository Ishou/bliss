package com.bliss.survey.api.routes

import com.bliss.survey.api.auth.UserIdKey
import com.bliss.survey.api.dto.CorrectifRejection
import com.bliss.survey.api.dto.ProblemDetails
import com.bliss.survey.api.dto.RatingRequest
import com.bliss.survey.api.dto.RatingResponse
import com.bliss.survey.api.respondProblem
import com.bliss.survey.application.usecases.SubmitRatingCommand
import com.bliss.survey.application.usecases.SubmitRatingResult
import com.bliss.survey.application.usecases.SubmitRatingUseCase
import com.bliss.survey.domain.model.FlagReason
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Rating
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.UserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.UUID

// POST /v1/items/{itemId}/rating — auth-optional rating submission.
// Anonymous callers attempting a `correctif` are rejected with 401.
fun Route.submitRatingRoute(execute: suspend (SubmitRatingCommand) -> SubmitRatingResult) {
    post("/v1/items/{itemId}/rating") {
        val itemUuid =
            runCatching { UUID.fromString(call.parameters["itemId"]) }.getOrNull()
                ?: return@post call.respondProblem(
                    HttpStatusCode.BadRequest,
                    ProblemDetails(
                        type = "about:blank",
                        title = "invalid item id",
                        status = HttpStatusCode.BadRequest.value,
                        detail = "itemId path parameter must be a UUID",
                    ),
                )

        val body = call.receive<RatingRequest>()
        val userId = call.attributes.getOrNull(UserIdKey)?.let { UserId(it) }

        if (userId == null && body.correctif != null) {
            return@post call.respondProblem(
                HttpStatusCode.Unauthorized,
                ProblemDetails(
                    type = "about:blank",
                    title = "sign-in required",
                    status = HttpStatusCode.Unauthorized.value,
                    detail = "Proposing a clue correction requires signing in.",
                ),
            )
        }

        val flag = body.flag?.let { runCatching { FlagReason.valueOf(it.uppercase()) }.getOrNull() }
        val correctifStyle = body.correctif?.let { runCatching { Style.valueOf(it.style.uppercase()) }.getOrNull() }
        if (body.correctif != null && correctifStyle == null) {
            return@post call.respondProblem(
                HttpStatusCode.BadRequest,
                ProblemDetails(
                    type = "about:blank",
                    title = "invalid style",
                    status = HttpStatusCode.BadRequest.value,
                    detail = "correctif.style must be a known Style value",
                ),
            )
        }

        val cmd =
            SubmitRatingCommand(
                itemId = ItemId(itemUuid),
                userId = userId,
                qualite = body.qualite,
                difficulte = body.difficulte,
                flag = flag,
                correctif = body.correctif?.let { it.text to correctifStyle!! },
                latencyMs = body.latencyMs,
            )

        when (val result = execute(cmd)) {
            is SubmitRatingResult.Accepted ->
                call.respond(HttpStatusCode.Created, result.rating.toResponse())

            is SubmitRatingResult.AlreadyExists ->
                call.respond(HttpStatusCode.Conflict, result.existing.toResponse())

            SubmitRatingResult.AnonCorrectifForbidden ->
                call.respondProblem(
                    HttpStatusCode.Unauthorized,
                    ProblemDetails(
                        type = "about:blank",
                        title = "sign-in required",
                        status = HttpStatusCode.Unauthorized.value,
                    ),
                )

            is SubmitRatingResult.CorrectifRejected ->
                call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    CorrectifRejection(
                        type = "about:blank",
                        title = "correctif rejected",
                        status = HttpStatusCode.UnprocessableEntity.value,
                        filterId = result.filterId,
                        reason = result.reason,
                    ),
                )

            SubmitRatingResult.ItemNotFound ->
                call.respondProblem(
                    HttpStatusCode.NotFound,
                    ProblemDetails(
                        type = "about:blank",
                        title = "item not found",
                        status = HttpStatusCode.NotFound.value,
                    ),
                )
        }
    }
}

// Production overload: lets Module.kt pass the use case as a class without
// the test seam leaking into the deployment binding.
fun Route.submitRatingRoute(useCase: SubmitRatingUseCase) = submitRatingRoute { cmd -> useCase.execute(cmd) }

private fun Rating.toResponse(): RatingResponse =
    RatingResponse(
        ratingId = id.value.toString(),
        itemId = itemId.value.toString(),
        submittedAs = submittedAs.name.lowercase(),
        proposedItemId = proposedItemId?.value?.toString(),
    )
