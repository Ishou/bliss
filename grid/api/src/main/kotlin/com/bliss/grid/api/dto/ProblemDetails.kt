package com.bliss.grid.api.dto

import kotlinx.serialization.Serializable

/** RFC 7807 `application/problem+json` body, per ADR-0003 §6 wire conventions. */
@Serializable
data class ProblemDetails(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
)
