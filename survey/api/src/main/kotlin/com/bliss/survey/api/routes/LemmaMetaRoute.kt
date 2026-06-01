package com.bliss.survey.api.routes

import com.bliss.survey.api.dto.LemmaMetaResponse
import com.bliss.survey.api.dto.ProblemDetails
import com.bliss.survey.api.respondProblem
import com.bliss.survey.application.usecases.GetLemmaMetaUseCase
import com.bliss.survey.application.usecases.LemmaMeta
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// GET /v1/lemma-meta/{mot} — open auth (vocabulary only, no PII); senses + sub-tags aggregated from prior ratings.
fun Route.lemmaMetaRoute(getLemmaMeta: suspend (String) -> LemmaMeta) {
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
            LemmaMetaResponse(priorSenses = meta.priorSenses, priorSubTags = meta.priorSubTags),
        )
    }
}

// Production overload; delegates to the lambda overload so use-case types stay out of the test scope.
fun Route.lemmaMetaRoute(getLemmaMeta: GetLemmaMetaUseCase) = lemmaMetaRoute(getLemmaMeta = { mot -> getLemmaMeta.execute(mot) })
