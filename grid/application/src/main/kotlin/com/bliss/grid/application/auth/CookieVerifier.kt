package com.bliss.grid.application.auth

/**
 * Verifies `__Secure-ws_session` against identity-api's whoami endpoint.
 *
 * Fail-closed contract: returns null for any non-OK outcome (missing cookie,
 * 401, 5xx, transport error, malformed body) and never throws.
 *
 * Two methods, one identity check:
 *
 *  - [verify] is the cached read path. Implementations may memoize positive
 *    and negative outcomes for a short TTL to absorb hot-key traffic.
 *  - [verifyFresh] bypasses the cache. Mutating endpoints must use it after
 *    taking the per-user advisory lock (Phase 6b §5 of the spec) so a revoked
 *    session can't slip a write through on a still-warm cache entry.
 */
interface CookieVerifier {
    /** Cached identity lookup; returns null on missing / invalid / unreachable. */
    suspend fun verify(rawCookieValue: String?): WhoAmI?

    /** Cache-bypassing identity lookup; required under per-user write locks. */
    suspend fun verifyFresh(rawCookieValue: String?): WhoAmI?
}
