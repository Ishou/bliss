package com.bliss.game.application.auth

import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.UserId

/**
 * Verifies the `__Secure-ws_session` cookie against identity-api's whoami.
 * Returns null for missing/invalid cookies, for identity-api 401, or when
 * identity-api is unreachable (fail-closed: treat as anon, never throw).
 */
interface CookieVerifier {
    suspend fun verify(rawCookieValue: String?): WhoAmI?
}

data class WhoAmI(
    val userId: UserId,
    val displayName: Pseudonym,
)
