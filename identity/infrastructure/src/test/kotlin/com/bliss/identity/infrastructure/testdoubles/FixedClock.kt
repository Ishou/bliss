package com.bliss.identity.infrastructure.testdoubles

import com.bliss.identity.application.ports.Clock
import java.time.Instant

// Mirrors com.bliss.identity.application.testdoubles.FixedClock (in application's test source set).
// Duplicated here because Kotlin/Gradle don't expose another module's test classes by default —
// a follow-up PR can consolidate via the `java-test-fixtures` plugin.
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
