package com.bliss.identity.infrastructure.time

import com.bliss.identity.application.ports.Clock
import java.time.Instant

object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}
