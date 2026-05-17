package com.bliss.identity.api.dto

import kotlinx.serialization.Serializable

// RFC 7807 problem-detail (ADR-0003 §6). Mirrors game/api + grid/api's shape.
@Serializable
data class ProblemDetails(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
)
