package com.bliss.grid.api.routes

import com.bliss.grid.application.auth.CookieVerifier
import com.bliss.grid.application.auth.WhoAmI

/**
 * Test double for [CookieVerifier]. [cached] feeds the read-path `verify`
 * call; [fresh] (defaults to [cached]) feeds the write-path `verifyFresh`
 * call so tests can simulate a session that passes the cache but fails
 * the under-lock re-verify.
 */
internal class FakeCookieVerifier(
    private val cached: WhoAmI? = null,
    private val fresh: WhoAmI? = cached,
) : CookieVerifier {
    override suspend fun verify(rawCookieValue: String?): WhoAmI? = if (rawCookieValue.isNullOrBlank()) null else cached

    override suspend fun verifyFresh(rawCookieValue: String?): WhoAmI? = if (rawCookieValue.isNullOrBlank()) null else fresh
}
