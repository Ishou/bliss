package com.bliss.grid.api

import com.bliss.grid.api.dto.ProblemDetails
import com.bliss.grid.api.infrastructure.Database
import com.bliss.grid.api.infrastructure.words.ResourceWordRepository
import com.bliss.grid.api.routes.health
import com.bliss.grid.api.routes.puzzles
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

/** Wires CORS, content negotiation (JSON), call logging, RFC 7807 errors, and routes. */
fun Application.module() {
    // Apply Flyway migrations against the CNPG-backed database (ADR-0013 §6,
    // ADR-0009). No-op when DATABASE_URL is unset (local dev, CI smoke tests).
    Database.start()

    install(CORS) {
        // Browsers block `https://wordsparrow.io` → `https://api.wordsparrow.io`
        // without these headers. Preview deploys do NOT call this API (per
        // ADR-0007 §5 — they use MSW mocks); only prod-served frontends and
        // local dev need allowance here.
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Options) // preflight
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)

        // Production frontends (Cloudflare Pages serving wordsparrow.io).
        allowHost("wordsparrow.io", schemes = listOf("https"))
        allowHost("www.wordsparrow.io", schemes = listOf("https"))

        // Local dev — Vite default port 5173 (frontend/package.json `dev`
        // script does not override; frontend/vite.config.ts has no `server`
        // block). If that ever changes, update this entry.
        allowHost("localhost:5173", schemes = listOf("http"))

        // No credentials = no cookies. The API is read-only public for now.
        allowCredentials = false
        maxAgeInSeconds = 86400 // cache preflight for 24h
    }

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
