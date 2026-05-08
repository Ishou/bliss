package com.bliss.grid.infrastructure.analytics

import com.bliss.grid.application.analytics.AnalyticsEventSink
import com.bliss.grid.domain.analytics.AnalyticsEvent
import java.util.UUID

/**
 * No-op sink for dev environments and tests where Matomo is not configured.
 * Events are silently dropped — no logging, no allocations beyond the call frame.
 */
class NoopAnalyticsAdapter : AnalyticsEventSink {
    override fun record(
        event: AnalyticsEvent,
        sessionId: UUID?,
    ) {
        // intentionally empty
    }
}
