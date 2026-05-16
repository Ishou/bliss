package com.bliss.identity.application.ports

import java.time.Instant

/**
 * Wall-clock port. Production binding (`SystemClock`) lands in Phase 3.
 * Tests use `FixedClock` to make time deterministic.
 */
fun interface Clock {
    fun now(): Instant
}
