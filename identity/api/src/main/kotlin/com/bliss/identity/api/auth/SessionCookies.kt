package com.bliss.identity.api.auth

import com.bliss.identity.domain.session.SessionId
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import java.time.Duration
import java.util.UUID

// Session-cookie issuer/clearer/reader for the `__Secure-ws_session` cookie.
//
// RFC 6265bis §4.1.3.2: `__Secure-` prefix REQUIRES `Secure` but PERMITS `Domain`.
// Scoping to `wordsparrow.io` lets the cookie travel to every subdomain
// (`auth.`, `game.`, `api.`, apex + www). The previous `__Host-` prefix
// host-locked the cookie to `auth.wordsparrow.io`, blocking cross-subdomain
// cookie-based authentication. See ADR-0044 amendment + Phase 6c spec.
object SessionCookies {
    const val NAME = "__Secure-ws_session"
    const val DOMAIN = "wordsparrow.io"

    fun issue(
        call: ApplicationCall,
        sessionId: SessionId,
        maxAge: Duration,
    ) {
        call.response.cookies.append(
            Cookie(
                name = NAME,
                value = sessionId.value.toString(),
                domain = DOMAIN,
                path = "/",
                httpOnly = true,
                secure = true,
                maxAge = maxAge.seconds.toInt(),
                extensions = mapOf("SameSite" to "Lax"),
                encoding = CookieEncoding.RAW,
            ),
        )
    }

    fun clear(call: ApplicationCall) {
        call.response.cookies.append(
            Cookie(
                name = NAME,
                value = "",
                domain = DOMAIN,
                path = "/",
                httpOnly = true,
                secure = true,
                maxAge = 0,
                extensions = mapOf("SameSite" to "Lax"),
                encoding = CookieEncoding.RAW,
            ),
        )
    }

    fun read(request: ApplicationRequest): SessionId? {
        val raw = request.cookies[NAME] ?: return null
        return try {
            SessionId(UUID.fromString(raw))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
