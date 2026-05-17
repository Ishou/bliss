package com.bliss.identity.api.auth

import com.bliss.identity.api.dto.ProblemDetails
import com.bliss.identity.application.usecases.WhoAmIError
import com.bliss.identity.application.usecases.WhoAmIQuery
import com.bliss.identity.application.usecases.WhoAmIResult
import com.bliss.identity.application.usecases.WhoAmIUseCase
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json

// Resolves the current session cookie to an authenticated user, or responds 401 + problem+json.
// Routes that require authentication call this at the top of their handler and short-circuit
// if it returns null.
suspend fun ApplicationCall.authenticated(
    whoAmI: WhoAmIUseCase,
    json: Json = AUTH_JSON,
): WhoAmIResult? {
    val sessionId = SessionCookies.read(request)
    if (sessionId == null) {
        respondProblem(json, HttpStatusCode.Unauthorized, "no_session", "No session cookie present.")
        return null
    }
    return try {
        whoAmI.execute(WhoAmIQuery(sessionId))
    } catch (e: WhoAmIError) {
        respondProblem(json, HttpStatusCode.Unauthorized, e.problemType(), e.message ?: "Unauthenticated.")
        null
    }
}

private suspend fun ApplicationCall.respondProblem(
    json: Json,
    status: HttpStatusCode,
    type: String,
    detail: String,
) {
    val problem =
        ProblemDetails(
            type = "https://wordsparrow.io/errors/$type",
            title = status.description,
            status = status.value,
            detail = detail,
            instance = request.local.uri,
        )
    respondText(
        text = json.encodeToString(ProblemDetails.serializer(), problem),
        contentType = ContentType.parse("application/problem+json"),
        status = status,
    )
}

private fun WhoAmIError.problemType(): String =
    when (this) {
        is WhoAmIError.SessionNotFound -> "session_not_found"
        is WhoAmIError.SessionRevoked -> "session_revoked"
        is WhoAmIError.SessionExpired -> "session_expired"
        is WhoAmIError.OrphanedSession -> "orphaned_session"
    }

private val AUTH_JSON: Json =
    Json {
        encodeDefaults = true // ADR-0003 §6
        ignoreUnknownKeys = true
        explicitNulls = false // omit null `detail`/`instance` rather than emit `null` literals
    }
