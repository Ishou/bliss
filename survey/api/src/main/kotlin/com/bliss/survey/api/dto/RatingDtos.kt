package com.bliss.survey.api.dto

import kotlinx.serialization.Serializable

// Wire DTOs for rating endpoints — camelCase per ADR-0003, matches openapi.yaml verbatim.
@Serializable
data class CorrectifPayload(
    val text: String,
    val style: String,
    val pos: String? = null,
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
    val campaignId: String,
    val undoToken: String? = null,
)

// Pairwise endpoints — 201 body shape per ADR-0059 + openapi PairRatingResponse schema.
@Serializable
data class PairRatingResponse(
    val campaignId: String,
    val undoToken: String? = null,
)

@Serializable
data class UndoActionRequest(
    val token: String,
)

// Pair-mode wire DTOs (ADR-0056 amendment 2026-05-28).
@Serializable
data class ItemPairDto(
    val mot: String,
    val left: ItemDto,
    val right: ItemDto,
)

@Serializable
data class PairRatingRequest(
    val leftItemId: String,
    val rightItemId: String,
    val verdict: String,
    val difficulte: Int,
    val latencyMs: Int,
)
