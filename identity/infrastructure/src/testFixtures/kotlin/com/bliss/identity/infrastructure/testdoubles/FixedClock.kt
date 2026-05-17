package com.bliss.identity.infrastructure.testdoubles

import com.bliss.identity.application.ports.Clock
import java.time.Instant

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
