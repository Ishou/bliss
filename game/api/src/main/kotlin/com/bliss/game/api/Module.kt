package com.bliss.game.api

import com.bliss.game.api.dto.ProblemDetails
import com.bliss.game.api.routes.health
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
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.seconds

/**
 * Wires CORS, content negotiation (JSON), call logging, RFC 7807 errors,
 * the WebSockets plugin, and routes. Mirrors `grid/api`'s Module shape so
 * the two services have the same observability and error envelope.
 *
 * No persistence wiring — game/ is in-memory in v1 (ADR-0018 §3); no
 * Database.start() unlike grid/api. Game-specific REST + WebSocket routes
 * land in Wave F (PR #9 / #10); only the health route is wired here.
 */
fun Application.module() {
    install(CORS) {
        // Browsers block `https://wordsparrow.io` → `https://game.wordsparrow.io`
        // without these headers. Frontend dev server runs on Vite's default 5173.
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options) // preflight
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)

        // Production frontends (Cloudflare Pages serving wordsparrow.io).
        allowHost("wordsparrow.io", schemes = listOf("https"))
        allowHost("www.wordsparrow.io", schemes = listOf("https"))

        // Local dev — Vite default port 5173 (mirrors grid/api).
        allowHost("localhost:5173", schemes = listOf("http"))

        // No credentials = no cookies. Sessions are sessionId-in-localStorage.
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

    // game/api is the FIRST WebSocket-using service in this repo (ADR-0018 §3,
    // ADR-0006). The WebSocket route itself lands in Wave F PR #10; installing
    // the plugin here lets that PR add the route without touching Module wiring.
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(StatusPages) {
        // RFC 7807 catch-all per ADR-0003 §6. IllegalArgumentException is the
        // canonical "client sent something invalid" signal from the application
        // layer's `require(...)` blocks; map it to 400 instead of leaking 500.
        exception<IllegalArgumentException> { call, cause ->
            val problem =
                ProblemDetails(
                    type = "about:blank",
                    title = "Bad Request",
                    status = HttpStatusCode.BadRequest.value,
                    detail = cause.message,
                    instance = call.request.local.uri,
                )
            call.respondText(
                text = Json.encodeToString(ProblemDetails.serializer(), problem),
                contentType = ContentType.parse("application/problem+json"),
                status = HttpStatusCode.BadRequest,
            )
        }
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

    routing {
        health(APP_VERSION)
    }
}
