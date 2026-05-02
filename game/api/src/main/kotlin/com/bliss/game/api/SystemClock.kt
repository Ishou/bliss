package com.bliss.game.api

import com.bliss.game.application.ports.Clock
import java.time.Instant

/**
 * Wall-clock [Clock] adapter — returns [Instant.now]. Lives in `:game:api`
 * for now (this PR is the first consumer); should move to
 * `:game:infrastructure` alongside the other adapters when a second consumer
 * appears.
 */
internal object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}
