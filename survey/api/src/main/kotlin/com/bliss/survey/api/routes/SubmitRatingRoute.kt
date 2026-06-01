package com.bliss.survey.api.routes

import com.bliss.survey.api.WIRE_JSON
import com.bliss.survey.api.auth.UserIdKey
import com.bliss.survey.api.dto.CorrectifRejection
import com.bliss.survey.api.dto.ProblemDetails
import com.bliss.survey.api.dto.RatingRequest
import com.bliss.survey.api.dto.RatingResponse
import com.bliss.survey.api.respondProblem
import com.bliss.survey.application.usecases.CorrectifInput
import com.bliss.survey.application.usecases.SubmitRatingCommand
import com.bliss.survey.application.usecases.SubmitRatingResult
import com.bliss.survey.application.usecases.SubmitRatingUseCase
import com.bliss.survey.domain.model.FlagReason
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Rating
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.UserId
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.UUID

// POST /v1/items/{itemId}/rating — auth-optional; anon + correctif rejected 401 before use-case.
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
        val correctifPos = body.correctif?.pos?.let { runCatching { Pos.valueOf(it.uppercase()) }.getOrNull() }
        if (body.correctif?.pos != null && correctifPos == null) {
            return@post call.respondProblem(
                HttpStatusCode.BadRequest,
                ProblemDetails(
                    type = "about:blank",
                    title = "invalid pos",
                    status = HttpStatusCode.BadRequest.value,
                    detail = "correctif.pos must be a known Pos value",
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
                correctif = body.correctif?.let { CorrectifInput(it.text, correctifStyle!!, correctifPos) },
                latencyMs = body.latencyMs,
                targetSenses = body.targetSenses ?: emptyList(),
            )

        when (val result = execute(cmd)) {
            is SubmitRatingResult.Accepted ->
                call.respond(HttpStatusCode.Created, result.rating.toResponse(undoToken = result.undoToken))

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

            SubmitRatingResult.AnonTargetSensesForbidden ->
                call.respondProblem(
                    HttpStatusCode.Unauthorized,
                    ProblemDetails(
                        type = "about:blank",
                        title = "sign-in required",
                        status = HttpStatusCode.Unauthorized.value,
                        detail = "Annotating senses requires signing in (ADR-0061).",
                    ),
                )

            is SubmitRatingResult.CorrectifRejected -> {
                val rejection =
                    CorrectifRejection(
                        type = "about:blank",
                        title = "correctif rejected",
                        status = HttpStatusCode.UnprocessableEntity.value,
                        filterId = result.filterId,
                        reason = result.reason,
                    )
                call.respondText(
                    text = WIRE_JSON.encodeToString(CorrectifRejection.serializer(), rejection),
                    contentType = ContentType.parse("application/problem+json"),
                    status = HttpStatusCode.UnprocessableEntity,
                )
            }

            SubmitRatingResult.ItemNotFound ->
                call.respondProblem(
                    HttpStatusCode.NotFound,
                    ProblemDetails(
                        type = "about:blank",
                        title = "item not found",
                        status = HttpStatusCode.NotFound.value,
                    ),
                )

            SubmitRatingResult.Locked ->
                call.respondProblem(
                    HttpStatusCode.Locked,
                    ProblemDetails(
                        type = "about:blank",
                        title = "campaign closed",
                        status = HttpStatusCode.Locked.value,
                        detail = "The sondage is paused while a training batch is being prepared.",
                    ),
                )
        }
    }
}

// Production overload so Module.kt can pass the concrete use case without exposing the test seam.
fun Route.submitRatingRoute(useCase: SubmitRatingUseCase) = submitRatingRoute { cmd -> useCase.execute(cmd) }

private fun Rating.toResponse(undoToken: String? = null): RatingResponse =
    RatingResponse(
        ratingId = id.value.toString(),
        itemId = itemId.value.toString(),
        submittedAs = submittedAs.name.lowercase(),
        proposedItemId = proposedItemId?.value?.toString(),
        campaignId =
            requireNotNull(campaignId) {
                "Accepted rating must have campaignId stamped by SubmitRatingUseCase"
            }.value.toString(),
        undoToken = undoToken,
    )
