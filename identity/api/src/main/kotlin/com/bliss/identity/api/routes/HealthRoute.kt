package com.bliss.identity.api.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.health() {
    get("/v1/health") {
        call.respondText(
            text = """{"status":"ok"}""",
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.OK,
        )
    }
}
