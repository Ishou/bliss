package com.bliss.grid.application.analytics

import com.bliss.grid.domain.analytics.AnalyticsEvent
import java.util.UUID

/**
 * Out-bound port for product analytics events ([AnalyticsEvent] subtypes). Adapters
 * (Matomo in production, Noop in dev/tests) live in `:grid:infrastructure`.
 *
 * Implementations MUST be fire-and-forget: a call to [record] never blocks the
 * caller, never propagates a failure, and never throws. If the analytics backend
 * is unreachable or returns an error, the implementation logs and drops the event.
 *
 * `sessionId` is optional. When present, the adapter computes a daily-rotated
 * salted hash (ADR-0025 §3) so the visitor is identifiable within a day but not
 * across days. The raw `sessionId` is never sent to the analytics backend.
 *
 * The `sessionId` here is a `UUID` — `grid/` does not expose a typed wrapper; the
 * value comes from the `X-Session-Id` request header (mirrors `game/`'s `SessionId`).
 */
interface AnalyticsEventSink {
    /**
     * Non-blocking by contract — implementations launch the work into their own
     * supervised scope and return immediately. Callers may invoke from synchronous
     * or coroutine code; never throws, never propagates failure.
     */
    fun record(
        event: AnalyticsEvent,
        sessionId: UUID? = null,
    )

    companion object {
        /**
         * In-process no-op sink. Use as a default in use-case constructors so existing
         * tests continue to compile, and as the production fallback when Matomo env vars
         * are not configured.
         */
        val Noop: AnalyticsEventSink =
            object : AnalyticsEventSink {
                override fun record(
                    event: AnalyticsEvent,
                    sessionId: UUID?,
                ) = Unit
            }
    }
}
