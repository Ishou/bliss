package com.bliss.game.application.auth

import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.UserId

/** Verifies __Secure-ws_session via identity-api whoami; returns null for missing/invalid cookie (fail-closed, never throws). */
interface CookieVerifier {
    /** Cached identity lookup; returns null on missing / invalid / unreachable. */
    suspend fun verify(rawCookieValue: String?): WhoAmI?

    /** Cache-bypassing identity lookup; refreshes cache on 200, invalidates on 401, fails closed on 5xx. */
    suspend fun verifyFresh(rawCookieValue: String?): WhoAmI?
}

data class WhoAmI(
    val userId: UserId,
    val displayName: Pseudonym,
)
