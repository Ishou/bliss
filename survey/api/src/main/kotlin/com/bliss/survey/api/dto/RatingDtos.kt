package com.bliss.survey.api.dto

import kotlinx.serialization.Serializable

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
    val latency_ms: Int,
)

@Serializable
data class RatingResponse(
    val rating_id: String,
    val item_id: String,
    val submitted_as: String,
    val proposed_item_id: String? = null,
)
