package com.bliss.identity.infrastructure.oidc

import com.nimbusds.jose.jwk.JWKSet
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory TTL cache for JWKS. The [fetch] lambda is injectable for tests.
 */
class JwksCache(
    private val ttl: Duration,
    private val clock: () -> Instant = Instant::now,
    private val fetch: suspend (uri: String) -> JWKSet,
) {
    private data class Entry(
        val set: JWKSet,
        val fetchedAt: Instant,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    suspend fun get(uri: String): JWKSet {
        val now = clock()
        val entry = cache[uri]
        if (entry != null && now.isBefore(entry.fetchedAt.plus(ttl))) {
            return entry.set
        }
        val fresh = fetch(uri)
        cache[uri] = Entry(fresh, now)
        return fresh
    }
}
