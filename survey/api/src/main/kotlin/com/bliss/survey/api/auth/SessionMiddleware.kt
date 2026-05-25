package com.bliss.survey.api.auth

import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.ApplicationRequest
import io.ktor.util.AttributeKey
import java.util.UUID

// Matches identity-api's `__Secure-ws_session` cookie (ADR-0044, 2026-05-18 amendment).
const val SESSION_COOKIE_NAME: String = "__Secure-ws_session"

// Absent on anonymous requests; guarded routes respond 401 themselves (ADR-0056 §5).
val UserIdKey: AttributeKey<UUID> = AttributeKey("survey.userId")

class SessionMiddlewareConfig {
    // Verifies a session cookie value, returning the user id when valid.
    // Defaults to a no-op so routing tests can install the plugin without
    // wiring identity-api.
    var verifyCookie: suspend (String) -> UUID? = { null }
}

// Auth-optional: sets UserIdKey when the cookie verifies; never short-circuits (ADR-0056).
val SessionMiddleware =
    createApplicationPlugin(
        name = "SurveySessionMiddleware",
        createConfiguration = ::SessionMiddlewareConfig,
    ) {
        val verify = pluginConfig.verifyCookie
        onCall { call ->
            val cookie = call.request.sessionCookie()
            if (!cookie.isNullOrBlank()) {
                verify(cookie)?.let { call.attributes.put(UserIdKey, it) }
            }
        }
    }

private fun ApplicationRequest.sessionCookie(): String? = cookies[SESSION_COOKIE_NAME]
