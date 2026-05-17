package com.bliss.identity.api.auth

import com.bliss.identity.domain.session.SessionId
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import java.time.Duration
import java.util.UUID

// Session-cookie issuer/clearer/reader for the `__Host-ws_session` cookie.
//
// RFC 6265bis §4.1.3: `__Host-` prefix REQUIRES `Path=/; Secure` and FORBIDS the
// `Domain` attribute. The cookie is host-locked to whichever host issued it; no
// `domain=...` is set here. Browsers reject `__Host-` cookies that carry a Domain.
object SessionCookies {
    const val NAME = "__Host-ws_session"

    fun issue(
        call: ApplicationCall,
        sessionId: SessionId,
        maxAge: Duration,
    ) {
        call.response.cookies.append(
            Cookie(
                name = NAME,
                value = sessionId.value.toString(),
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
