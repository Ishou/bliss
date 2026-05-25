package com.bliss.survey.api.routes

import com.bliss.survey.api.auth.UserIdKey
import com.bliss.survey.api.dto.ItemDto
import com.bliss.survey.application.usecases.GetNextItemUseCase
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.UserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.util.UUID

// GET /v1/items/next — auth-optional pull from the rating pool (ADR-0056).
// Anonymous callers may pass `excluded=<uuid,uuid>` for client-side dedup.
fun Route.nextItemRoute(useCase: GetNextItemUseCase) {
    get("/v1/items/next") {
        val userId = call.attributes.getOrNull(UserIdKey)?.let { UserId(it) }
        val excluded =
            call.request.queryParameters["excluded"]
                ?.split(",")
                ?.mapNotNull { token -> parseItemId(token.trim()) }
                ?.toSet()
                ?: emptySet()

        val item = useCase.execute(forUser = userId, locallyExcluded = excluded)
        if (item == null) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.OK, item.toDto())
        }
    }
}

private fun parseItemId(raw: String): ItemId? = runCatching { ItemId(UUID.fromString(raw)) }.getOrNull()

internal fun SurveyItem.toDto(): ItemDto =
    ItemDto(
        itemId = id.value.toString(),
        mot = mot,
        definition = definition,
        pos = pos.name.lowercase(),
        categorie = categorie.name.lowercase(),
        style = style.name.lowercase(),
        forceClaimed = forceClaimed,
        longueur = longueur,
        tier = tier.name.lowercase(),
        isCalibration = isCalibration,
    )
