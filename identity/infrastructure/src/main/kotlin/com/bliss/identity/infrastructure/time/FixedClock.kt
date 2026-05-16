package com.bliss.identity.infrastructure.time

import com.bliss.identity.application.ports.Clock
import java.time.Instant

/**
 * Test double — returns a fixed `Instant`, optionally advanced manually.
 * Production binding (`SystemClock`) lands in Phase 3.
 */
class FixedClock(
    private var current: Instant,
) : Clock {
    override fun now(): Instant = current

    fun advanceBy(seconds: Long) {
        current = current.plusSeconds(seconds)
    }

    fun set(instant: Instant) {
        current = instant
    }
}
