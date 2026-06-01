package com.bliss.survey.api

import com.bliss.survey.api.auth.SessionMiddleware
import com.bliss.survey.api.config.SurveyApiConfig
import com.bliss.survey.api.dto.ProblemDetails
import com.bliss.survey.api.routes.getCurrentCampaignRoute
import com.bliss.survey.api.routes.getNextPairRoute
import com.bliss.survey.api.routes.healthRoute
import com.bliss.survey.api.routes.lemmaMetaRoute
import com.bliss.survey.api.routes.meContributionsRoute
import com.bliss.survey.api.routes.mePreferencesRoute
import com.bliss.survey.api.routes.meProgressRoute
import com.bliss.survey.api.routes.nextItemRoute
import com.bliss.survey.api.routes.submitPairRatingRoute
import com.bliss.survey.api.routes.submitRatingRoute
import com.bliss.survey.api.routes.undoActionRoute
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

// Install order mirrors identity/api: CallLogging → ContentNegotiation → CORS → StatusPages → SessionMiddleware.
fun Application.surveyApiModule(
    wiring: Wiring,
    config: SurveyApiConfig,
) {
    install(CallLogging) {
        level = Level.INFO
    }

    install(ContentNegotiation) {
        json(WIRE_JSON)
    }

    installSurveyCors(config)

    install(StatusPages) {
        // RFC 7807 catch-all per ADR-0003 §6.
        exception<IllegalArgumentException> { call, cause ->
            val problem =
                ProblemDetails(
                    type = "about:blank",
                    title = "invalid request",
                    status = HttpStatusCode.BadRequest.value,
                    detail = cause.message,
                    instance = call.request.local.uri,
                )
            call.respondText(
                text = WIRE_JSON.encodeToString(ProblemDetails.serializer(), problem),
                contentType = ContentType.parse("application/problem+json"),
                status = HttpStatusCode.BadRequest,
            )
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log
                .error("unhandled_exception", cause)
            val problem =
                ProblemDetails(
                    type = "about:blank",
                    title = "internal error",
                    status = HttpStatusCode.InternalServerError.value,
                    detail = "An unexpected error occurred.",
                    instance = call.request.local.uri,
                )
            call.respondText(
                text = WIRE_JSON.encodeToString(ProblemDetails.serializer(), problem),
                contentType = ContentType.parse("application/problem+json"),
                status = HttpStatusCode.InternalServerError,
            )
        }
    }

    install(SessionMiddleware) {
        verifyCookie = wiring.verifyCookie
    }

    monitor.subscribe(ApplicationStopped) {
        wiring.userDeletedConsumer?.stop()
        wiring.userRoleChangedConsumer?.stop()
        wiring.closeNats()
    }

    routing {
        healthRoute()
        nextItemRoute(wiring.getNextItem)
        submitRatingRoute(wiring.submitRating)
        getNextPairRoute(wiring.getNextPair)
        submitPairRatingRoute(wiring.submitPairRating)
        undoActionRoute(wiring.undoAction)
        getCurrentCampaignRoute(wiring.getCurrentCampaign)
        lemmaMetaRoute(wiring.getLemmaMeta)
        meProgressRoute(wiring.userProgress)
        meContributionsRoute(wiring.items)
        mePreferencesRoute(wiring.proposedBy)
    }
}

// null (absent vs null are distinct on the wire) and encodeDefaults keep all required fields present — ADR-0003 §6.
internal val WIRE_JSON: Json =
    Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        explicitNulls = true // null ("not yet") must appear on wire; absence ≠ null (ADR-0003 §6)
        encodeDefaults = true
    }

// Responds with RFC 7807 application/problem+json, bypassing ContentNegotiation which would produce application/json.
internal suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    problem: ProblemDetails,
) = respondText(
    text = WIRE_JSON.encodeToString(ProblemDetails.serializer(), problem),
    contentType = ContentType.parse("application/problem+json"),
    status = status,
)

internal fun Application.installSurveyCors(config: SurveyApiConfig) {
    install(CORS) {
        for (origin in config.allowedOrigins) {
            val parsed = parseOrigin(origin)
            if (parsed != null) {
                allowHost(parsed.hostPort, schemes = listOf(parsed.scheme))
            }
        }
        // PATCH on /v1/me/preferences (Ktor allows GET/POST/HEAD by default).
        allowMethod(HttpMethod.Patch)

        // ADR-0048: wildcard predicate echoes request headers verbatim; never emits literal "*".
        allowHeaders { true }

        allowCredentials = true
        allowNonSimpleContentTypes = true
        maxAgeInSeconds = 600
    }
}

private data class ParsedOrigin(
    val scheme: String,
    val hostPort: String,
)

// Splits "https://host" → ("https", "host"); returns null for unparseable input.
private fun parseOrigin(raw: String): ParsedOrigin? {
    val idx = raw.indexOf("://")
    if (idx <= 0) return null
    val scheme = raw.substring(0, idx)
    val rest = raw.substring(idx + 3).trimEnd('/')
    if (rest.isBlank()) return null
    return ParsedOrigin(scheme = scheme, hostPort = rest)
}
