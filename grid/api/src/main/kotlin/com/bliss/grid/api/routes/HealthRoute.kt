package com.bliss.grid.api.routes

import com.bliss.grid.api.dto.HealthResponse
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Duration
import java.time.Instant

private val startedAt: Instant = Instant.now()

/**
 * `GET /v1/health` — liveness probe (Fly healthcheck + Dockerfile HEALTHCHECK,
 * ADR-0007 §3). Out-of-OpenAPI by design; ops endpoints live below the
 * contract layer.
 */
fun Route.health(version: String) {
    get("/v1/health") {
        val uptime = Duration.between(startedAt, Instant.now())
        call.respond(
            HealthResponse(
                status = "ok",
                version = version,
                uptime = "PT${uptime.seconds}S",
            ),
        )
    }
}
