package com.bliss.survey.api.routes

import com.bliss.survey.api.auth.UserIdKey
import com.bliss.survey.api.dto.ContributionItem
import com.bliss.survey.api.dto.ProblemDetails
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.domain.model.UserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// GET /v1/me/contributions — auth-required listing of rater-proposed items.
fun Route.meContributionsRoute(items: SurveyItemRepository) {
    get("/v1/me/contributions") {
        val userId =
            call.attributes.getOrNull(UserIdKey)
                ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    ProblemDetails(
                        type = "about:blank",
                        title = "sign-in required",
                        status = HttpStatusCode.Unauthorized.value,
                    ),
                )
        val list = items.listProposedByUser(UserId(userId))
        call.respond(
            HttpStatusCode.OK,
            list.map { c ->
                ContributionItem(
                    itemId =
                        c.item.id.value
                            .toString(),
                    mot = c.item.mot,
                    definition = c.item.definition,
                    pos =
                        c.item.pos.name
                            .lowercase(),
                    categorie =
                        c.item.categorie.name
                            .lowercase(),
                    style =
                        c.item.style.name
                            .lowercase(),
                    optedOut = c.optedOut,
                    kCoverage = c.kCoverage,
                    createdAt = c.item.createdAt.toString(),
                )
            },
        )
    }
}
