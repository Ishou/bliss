package com.bliss.survey.api.routes

import com.bliss.survey.api.auth.UserIdKey
import com.bliss.survey.api.dto.LemmaMetaResponse
import com.bliss.survey.api.dto.ProblemDetails
import com.bliss.survey.api.dto.SubTagsRequest
import com.bliss.survey.api.respondProblem
import com.bliss.survey.application.usecases.GetLemmaMetaUseCase
import com.bliss.survey.application.usecases.UpsertSubTagsResult
import com.bliss.survey.application.usecases.UpsertSubTagsUseCase
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.model.WordMeta
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

// GET /v1/lemma-meta/{mot} — open auth (vocabulary only, no PII). PUT — maintainer-only (ADR-0060 role gate).
fun Route.lemmaMetaRoute(
    getLemmaMeta: suspend (String) -> WordMeta?,
    upsertSubTags: suspend (String, List<String>, UserId) -> UpsertSubTagsResult,
) {
    get("/v1/lemma-meta/{mot}") {
        val mot = call.parameters["mot"]
        if (mot.isNullOrBlank()) {
            return@get call.respondProblem(
                HttpStatusCode.BadRequest,
                ProblemDetails(
                    type = "about:blank",
                    title = "invalid mot",
                    status = HttpStatusCode.BadRequest.value,
                    detail = "mot path parameter must be non-blank",
                ),
            )
        }
        val meta = getLemmaMeta(mot)
        call.respond(
            HttpStatusCode.OK,
            LemmaMetaResponse(
                priorSenses = meta?.senseInventory ?: emptyList(),
                priorSubTags = meta?.subTags ?: emptyList(),
            ),
        )
    }

    put("/v1/lemma-meta/{mot}") {
        val mot = call.parameters["mot"]
        if (mot.isNullOrBlank()) {
            return@put call.respondProblem(
                HttpStatusCode.BadRequest,
                ProblemDetails(
                    type = "about:blank",
                    title = "invalid mot",
                    status = HttpStatusCode.BadRequest.value,
                    detail = "mot path parameter must be non-blank",
                ),
            )
        }
        val userId =
            call.attributes.getOrNull(UserIdKey)?.let { UserId(it) }
                ?: return@put call.respondProblem(
                    HttpStatusCode.Unauthorized,
                    ProblemDetails(
                        type = "about:blank",
                        title = "sign-in required",
                        status = HttpStatusCode.Unauthorized.value,
                    ),
                )
        val body = call.receive<SubTagsRequest>()
        when (upsertSubTags(mot, body.subTags, userId)) {
            UpsertSubTagsResult.Ok -> call.respond(HttpStatusCode.NoContent)
            UpsertSubTagsResult.Forbidden ->
                call.respondProblem(
                    HttpStatusCode.Forbidden,
                    ProblemDetails(
                        type = "about:blank",
                        title = "maintainer role required",
                        status = HttpStatusCode.Forbidden.value,
                        detail = "Sub-tag upsert requires the maintainer role (ADR-0060).",
                    ),
                )
        }
    }
}

// Production overload; delegates to the lambda overload so use-case types stay out of the test scope.
fun Route.lemmaMetaRoute(
    getLemmaMeta: GetLemmaMetaUseCase,
    upsertSubTags: UpsertSubTagsUseCase,
) = lemmaMetaRoute(
    getLemmaMeta = { mot -> getLemmaMeta.execute(mot) },
    upsertSubTags = { mot, tags, userId -> upsertSubTags.execute(mot, tags, userId) },
)
