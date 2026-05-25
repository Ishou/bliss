package com.bliss.survey.infrastructure.identity

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Wraps [IdentityClient] with a per-cookie TTL cache (ADR-0044). Default TTL: 30 s. */
class CachedSessionVerifier(
    private val client: IdentityClient,
    private val ttl: Duration = Duration.ofSeconds(30),
    private val clock: () -> Instant = Instant::now,
) {
    private data class Entry(
        val userId: UUID?,
        val expiresAt: Instant,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    suspend fun verify(cookieValue: String?): UUID? {
        if (cookieValue.isNullOrBlank()) return null
        val now = clock()
        val cached = cache[cookieValue]
        if (cached != null) {
            if (cached.expiresAt.isAfter(now)) return cached.userId
            cache.remove(cookieValue, cached)
        }
        val resolved = client.verifySession(cookieValue)
        cache[cookieValue] = Entry(resolved, now.plus(ttl))
        return resolved
    }
}
