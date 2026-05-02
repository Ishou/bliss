package com.bliss.game.api.routes

import com.bliss.game.api.dto.HealthResponse
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Duration
import java.time.Instant

private val startedAt: Instant = Instant.now()

// Out-of-OpenAPI: ops endpoint, also serves Dockerfile HEALTHCHECK (ADR-0007 §3).
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
