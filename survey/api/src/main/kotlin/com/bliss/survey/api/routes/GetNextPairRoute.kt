package com.bliss.survey.api.routes

import com.bliss.survey.api.auth.UserIdKey
import com.bliss.survey.api.dto.ItemPairDto
import com.bliss.survey.application.usecases.GetNextPairUseCase
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.ItemPair
import com.bliss.survey.domain.model.UserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.util.UUID

// GET /v1/items/pairs/next — auth-optional; excluded= query for client-side dedup (ADR-0056 amendment 2026-05-28).
fun Route.getNextPairRoute(useCase: GetNextPairUseCase) {
    get("/v1/items/pairs/next") {
        val userId = call.attributes.getOrNull(UserIdKey)?.let { UserId(it) }
        val excluded =
            call.request.queryParameters["excluded"]
                ?.split(",")
                ?.mapNotNull { token -> parsePairItemId(token.trim()) }
                ?.toSet()
                ?: emptySet()

        val pair = useCase.execute(forUser = userId, locallyExcluded = excluded)
        if (pair == null) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.OK, pair.toDto())
        }
    }
}

private fun parsePairItemId(raw: String): ItemId? = runCatching { ItemId(UUID.fromString(raw)) }.getOrNull()

internal fun ItemPair.toDto(): ItemPairDto =
    ItemPairDto(
        mot = mot,
        left = left.toDto(),
        right = right.toDto(),
    )
