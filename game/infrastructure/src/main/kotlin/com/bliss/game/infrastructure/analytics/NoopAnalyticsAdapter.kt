package com.bliss.game.infrastructure.analytics

import com.bliss.game.application.ports.AnalyticsEventSink
import com.bliss.game.domain.SessionId
import com.bliss.game.domain.analytics.AnalyticsEvent

/**
 * No-op sink for dev environments and tests where Matomo is not configured.
 * Events are silently dropped — no logging, no allocations beyond the call frame.
 */
class NoopAnalyticsAdapter : AnalyticsEventSink {
    override fun record(
        event: AnalyticsEvent,
        sessionId: SessionId?,
    ) {
        // intentionally empty
    }
}
