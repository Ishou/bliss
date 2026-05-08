package com.bliss.grid.api.dto

import kotlinx.serialization.Serializable

/**
 * Wire shapes for `POST /v1/puzzles/{puzzleId}/hints` per `grid/api/openapi.yaml`.
 * The route deserializes [WordHintRequest], invokes the use case, and emits
 * [WordHintResult] on the 200 path or a `ProblemDetails` on 400 / 404 / 429.
 */
@Serializable
data class WordHintRequest(
    val word: String,
)

@Serializable
data class WordHintResult(
    val word: String,
    val exists: Boolean,
    val hintsRemaining: Int,
)
