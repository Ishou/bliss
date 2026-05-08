package com.bliss.grid.api.dto

import kotlinx.serialization.Serializable

/**
 * Wire shapes for `POST /v1/puzzles/{puzzleId}/hints` per `grid/api/openapi.yaml`.
 * The route deserializes [RevealCellHintRequest], invokes the use case, and
 * emits [RevealCellHintResult] on the 200 path or a `ProblemDetails` on
 * 400 / 404 / 429.
 */
@Serializable
data class RevealCellHintRequest(
    val row: Int,
    val column: Int,
)

@Serializable
data class RevealCellHintResult(
    val row: Int,
    val column: Int,
    val letter: String,
    val hintsRemaining: Int,
)
