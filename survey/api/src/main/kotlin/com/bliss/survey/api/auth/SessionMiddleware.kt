package com.bliss.survey.api.auth

import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.ApplicationRequest
import io.ktor.util.AttributeKey
import java.util.UUID

// Survey session cookie name. Authoritative issuer is identity-api (ADR-0044
// amendment 2026-05-18) which uses `__Secure-ws_session`; this value matches
// the openapi.yaml security scheme and what the browser actually sends.
const val SESSION_COOKIE_NAME: String = "__Secure-ws_session"

// Attribute that downstream routes read to discover the authenticated user.
// Absent on anonymous calls; routes on `/v1/me/*` must guard for absence and
// respond 401 themselves. ADR-0056 §5.
val UserIdKey: AttributeKey<UUID> = AttributeKey("survey.userId")

class SessionMiddlewareConfig {
    // Verifies a session cookie value, returning the user id when valid.
    // Defaults to a no-op so routing tests can install the plugin without
    // wiring identity-api.
    var verifyCookie: suspend (String) -> UUID? = { null }
}

// Auth-optional session middleware (ADR-0056). Sets [UserIdKey] when the
// cookie verifies; leaves it absent otherwise. Never short-circuits the
// request — guarded routes enforce 401 themselves.
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
