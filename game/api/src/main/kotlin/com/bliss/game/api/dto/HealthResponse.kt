package com.bliss.game.api.dto

import kotlinx.serialization.Serializable

// Out-of-OpenAPI: ops endpoint. uptime = ISO-8601 duration (ADR-0003 §6).
@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val uptime: String,
)
