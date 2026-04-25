package com.bliss.grid.api.dto

import kotlinx.serialization.Serializable

/**
 * `/v1/health` response body. Intentionally out-of-OpenAPI: ops endpoints
 * live below the contract layer. `uptime` is an ISO-8601 duration
 * (`PT<seconds>S`) per ADR-0003 §6.
 */
@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val uptime: String,
)
