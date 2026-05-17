package com.bliss.identity.infrastructure.time

import assertk.assertThat
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class SystemClockTest {
    @Test
    fun `now returns the current wall-clock time within a sane window`() {
        val before = Instant.now()
        val sampled = SystemClock().now()
        val after = Instant.now()
        assertThat(sampled).isGreaterThanOrEqualTo(before)
        assertThat(sampled).isLessThan(after.plus(Duration.ofSeconds(1)))
    }

    @Test
    fun `now is monotonic across two calls`() {
        val clock = SystemClock()
        val first = clock.now()
        val second = clock.now()
        assertThat(second).isGreaterThanOrEqualTo(first)
    }
}
