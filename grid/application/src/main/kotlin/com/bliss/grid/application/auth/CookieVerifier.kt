package com.bliss.grid.application.auth

/** Verifies `__Secure-ws_session` against identity-api's whoami endpoint; returns null on any failure (fail-closed). */
interface CookieVerifier {
    /** Cached identity lookup; returns null on missing / invalid / unreachable. */
    suspend fun verify(rawCookieValue: String?): WhoAmI?

    /** Cache-bypassing identity lookup; required under per-user write locks. */
    suspend fun verifyFresh(rawCookieValue: String?): WhoAmI?
}
