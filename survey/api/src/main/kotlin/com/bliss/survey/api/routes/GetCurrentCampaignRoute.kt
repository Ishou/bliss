package com.bliss.survey.api.routes

import com.bliss.survey.api.dto.CampaignResponse
import com.bliss.survey.api.dto.ProblemDetails
import com.bliss.survey.api.respondProblem
import com.bliss.survey.application.usecases.GetCurrentCampaignUseCase
import com.bliss.survey.domain.model.Campaign
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// GET /v1/campaign/current — returns the lock state for the frontend (ADR-0059).
fun Route.getCurrentCampaignRoute(fetch: suspend () -> Campaign?) {
    get("/v1/campaign/current") {
        val campaign = fetch()
        if (campaign == null) {
            call.respondProblem(
                HttpStatusCode.ServiceUnavailable,
                ProblemDetails(
                    type = "about:blank",
                    title = "no campaign",
                    status = HttpStatusCode.ServiceUnavailable.value,
                    detail = "No campaign has ever been opened.",
                ),
            )
            return@get
        }
        call.respond(
            HttpStatusCode.OK,
            CampaignResponse(
                campaignId = campaign.id.value.toString(),
                batchLabel = campaign.batchLabel,
                openedAt = campaign.openedAt.toString(),
                closedAt = campaign.closedAt?.toString(),
            ),
        )
    }
}

fun Route.getCurrentCampaignRoute(useCase: GetCurrentCampaignUseCase) = getCurrentCampaignRoute { useCase.execute() }
