package com.bliss.survey.api.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// Plain liveness probe, mirrors grid/identity/game `/v1/health`. Kubernetes
// probes are configured by the Helm chart (ADR-0009).
fun Route.healthRoute() {
    get("/v1/health") {
        call.respondText(
            text = """{"status":"ok"}""",
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.OK,
        )
    }
}
