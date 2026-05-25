package com.bliss.survey.api.dto

import kotlinx.serialization.Serializable

// Wire DTOs for rating endpoints — camelCase per ADR-0003, matches openapi.yaml verbatim.
@Serializable
data class CorrectifPayload(
    val text: String,
    val style: String,
)

@Serializable
data class RatingRequest(
    val qualite: Int,
    val difficulte: Int,
    val flag: String? = null,
    val correctif: CorrectifPayload? = null,
    val latencyMs: Int,
)

@Serializable
data class RatingResponse(
    val ratingId: String,
    val itemId: String,
    val submittedAs: String,
    val proposedItemId: String? = null,
)
