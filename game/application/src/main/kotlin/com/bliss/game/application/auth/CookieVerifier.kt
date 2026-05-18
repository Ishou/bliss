package com.bliss.game.application.auth

import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.UserId

/** Verifies __Secure-ws_session via identity-api whoami; returns null for missing/invalid cookie (fail-closed, never throws). */
interface CookieVerifier {
    suspend fun verify(rawCookieValue: String?): WhoAmI?
}

data class WhoAmI(
    val userId: UserId,
    val displayName: Pseudonym,
)
