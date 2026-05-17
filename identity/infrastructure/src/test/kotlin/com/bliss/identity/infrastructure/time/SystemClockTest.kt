package com.bliss.identity.infrastructure.time

import assertk.assertThat
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import org.junit.jupiter.api.Test
import java.time.Instant

class SystemClockTest {
    @Test
    fun `now returns the current wall-clock time within a sane window`() {
        val before = Instant.now()
        val sampled = SystemClock.now()
        val after = Instant.now()
        assertThat(sampled).isGreaterThanOrEqualTo(before)
        assertThat(sampled).isLessThanOrEqualTo(after)
    }

    @Test
    fun `now is monotonic across two calls`() {
        val first = SystemClock.now()
        val second = SystemClock.now()
        assertThat(second).isGreaterThanOrEqualTo(first)
    }
}
