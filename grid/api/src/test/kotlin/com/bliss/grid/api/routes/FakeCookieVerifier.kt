package com.bliss.grid.api.routes

import com.bliss.grid.application.auth.CookieVerifier
import com.bliss.grid.application.auth.WhoAmI

/** Test double for [CookieVerifier]; [fresh] defaults to [cached] but can be set independently to simulate a revoked session. */
internal class FakeCookieVerifier(
    private val cached: WhoAmI? = null,
    private val fresh: WhoAmI? = cached,
) : CookieVerifier {
    override suspend fun verify(rawCookieValue: String?): WhoAmI? = if (rawCookieValue.isNullOrBlank()) null else cached

    override suspend fun verifyFresh(rawCookieValue: String?): WhoAmI? = if (rawCookieValue.isNullOrBlank()) null else fresh
}
