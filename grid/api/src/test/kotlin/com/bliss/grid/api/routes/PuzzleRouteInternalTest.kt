package com.bliss.grid.api.routes

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

/** Unit tests for internal route helpers. */
class PuzzleRouteInternalTest {
    @Test
    fun `defaultConstraints has expected dimensions`() {
        val c = defaultConstraints()
        assertThat(c.width).isEqualTo(PUZZLE_WIDTH)
        assertThat(c.height).isEqualTo(PUZZLE_HEIGHT)
    }
}
