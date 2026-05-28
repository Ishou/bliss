package com.bliss.survey.api.routes

import com.bliss.survey.api.auth.UserIdKey
import com.bliss.survey.api.dto.PairRatingRequest
import com.bliss.survey.api.dto.ProblemDetails
import com.bliss.survey.api.respondProblem
import com.bliss.survey.application.usecases.SubmitPairRatingCommand
import com.bliss.survey.application.usecases.SubmitPairRatingResult
import com.bliss.survey.application.usecases.SubmitPairRatingUseCase
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.PairVerdict
import com.bliss.survey.domain.model.UserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.UUID

// POST /v1/ratings/pair — auth-optional; verdict dispatch handled by the use case (ADR-0056 amendment 2026-05-28).
fun Route.submitPairRatingRoute(execute: suspend (SubmitPairRatingCommand) -> SubmitPairRatingResult) {
    post("/v1/ratings/pair") {
        val body = call.receive<PairRatingRequest>()
        val leftUuid =
            runCatching { UUID.fromString(body.leftItemId) }.getOrNull()
                ?: return@post call.respondProblem(
                    HttpStatusCode.BadRequest,
                    ProblemDetails(
                        type = "about:blank",
                        title = "invalid item id",
                        status = HttpStatusCode.BadRequest.value,
                        detail = "leftItemId must be a UUID",
                    ),
                )
        val rightUuid =
            runCatching { UUID.fromString(body.rightItemId) }.getOrNull()
                ?: return@post call.respondProblem(
                    HttpStatusCode.BadRequest,
                    ProblemDetails(
                        type = "about:blank",
                        title = "invalid item id",
                        status = HttpStatusCode.BadRequest.value,
                        detail = "rightItemId must be a UUID",
                    ),
                )
        val verdict =
            runCatching { PairVerdict.valueOf(body.verdict.uppercase()) }.getOrNull()
                ?: return@post call.respondProblem(
                    HttpStatusCode.BadRequest,
                    ProblemDetails(
                        type = "about:blank",
                        title = "invalid verdict",
                        status = HttpStatusCode.BadRequest.value,
                        detail = "verdict must be one of LEFT_WINS|RIGHT_WINS|BOTH_GOOD|BOTH_BAD|SKIP",
                    ),
                )
        if (body.difficulte !in 1..5) {
            return@post call.respondProblem(
                HttpStatusCode.BadRequest,
                ProblemDetails(
                    type = "about:blank",
                    title = "invalid difficulte",
                    status = HttpStatusCode.BadRequest.value,
                    detail = "difficulte must be in 1..5",
                ),
            )
        }
        if (body.latencyMs < 0) {
            return@post call.respondProblem(
                HttpStatusCode.BadRequest,
                ProblemDetails(
                    type = "about:blank",
                    title = "invalid latencyMs",
                    status = HttpStatusCode.BadRequest.value,
                    detail = "latencyMs must be non-negative",
                ),
            )
        }

        val userId = call.attributes.getOrNull(UserIdKey)?.let { UserId(it) }
        val cmd =
            SubmitPairRatingCommand(
                leftItemId = ItemId(leftUuid),
                rightItemId = ItemId(rightUuid),
                userId = userId,
                verdict = verdict,
                difficulte = body.difficulte,
                latencyMs = body.latencyMs,
            )

        when (execute(cmd)) {
            SubmitPairRatingResult.Recorded, SubmitPairRatingResult.Skipped ->
                call.respond(HttpStatusCode.NoContent)

            SubmitPairRatingResult.AlreadyExists ->
                call.respondProblem(
                    HttpStatusCode.Conflict,
                    ProblemDetails(
                        type = "about:blank",
                        title = "pair already rated",
                        status = HttpStatusCode.Conflict.value,
                    ),
                )

            SubmitPairRatingResult.ItemNotFound ->
                call.respondProblem(
                    HttpStatusCode.NotFound,
                    ProblemDetails(
                        type = "about:blank",
                        title = "item not found",
                        status = HttpStatusCode.NotFound.value,
                    ),
                )

            SubmitPairRatingResult.PairMotMismatch ->
                call.respondProblem(
                    HttpStatusCode.BadRequest,
                    ProblemDetails(
                        type = "about:blank",
                        title = "pair mot mismatch",
                        status = HttpStatusCode.BadRequest.value,
                        detail = "leftItemId and rightItemId must refer to the same mot",
                    ),
                )

            SubmitPairRatingResult.SameItem ->
                call.respondProblem(
                    HttpStatusCode.BadRequest,
                    ProblemDetails(
                        type = "about:blank",
                        title = "same item",
                        status = HttpStatusCode.BadRequest.value,
                        detail = "leftItemId and rightItemId must differ",
                    ),
                )
        }
    }
}

// concrete overload that takes the use case directly, keeping the lambda seam out of production wiring
fun Route.submitPairRatingRoute(useCase: SubmitPairRatingUseCase) = submitPairRatingRoute { cmd -> useCase.execute(cmd) }
