package com.bliss.grid.api

import com.bliss.grid.api.dto.ProblemDetails
import com.bliss.grid.api.infrastructure.words.ResourceWordRepository
import com.bliss.grid.api.routes.health
import com.bliss.grid.api.routes.puzzles
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

/** Wires content negotiation (JSON), call logging, RFC 7807 errors, and routes. */
fun Application.module() {
    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = false
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }

    install(CallLogging) {
        level = Level.INFO
    }

    install(StatusPages) {
        // RFC 7807 catch-all per ADR-0003 §6.
        exception<Throwable> { call, cause ->
            val problem =
                ProblemDetails(
                    type = "about:blank",
                    title = "Internal Server Error",
                    status = HttpStatusCode.InternalServerError.value,
                    detail = cause.message,
                    instance = call.request.local.uri,
                )
            call.respondText(
                text = Json.encodeToString(ProblemDetails.serializer(), problem),
                contentType = ContentType.parse("application/problem+json"),
                status = HttpStatusCode.InternalServerError,
            )
        }
    }

    val version =
        environment.config
            .propertyOrNull("ktor.application.version")
            ?.getString()
            ?: System.getProperty("grid.api.version")
            ?: "unknown"

    val wordRepository = ResourceWordRepository.frenchFromClasspath()

    routing {
        health(version)
        puzzles(wordRepository)
    }
}
