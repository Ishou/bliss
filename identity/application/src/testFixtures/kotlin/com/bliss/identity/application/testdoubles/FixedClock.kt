package com.bliss.identity.application.testdoubles

import com.bliss.identity.application.ports.Clock
import java.time.Instant

/** Returns a fixed [Instant], optionally advanced manually. */
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
