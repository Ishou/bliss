package com.bliss.identity.infrastructure.time

import com.bliss.identity.application.ports.Clock
import java.time.Instant

/**
 * Production binding of [Clock] — delegates to `java.time.Instant.now()`.
 * Tests inject `FixedClock` (from testFixtures) instead so time is
 * deterministic.
 */
class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}
