package com.bliss.grid.domain.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class DirectionTest {
    @Test
    fun `RIGHT offset moves one column to the right`() {
        assertThat(Direction.RIGHT.offset).isEqualTo(Position(Row(0), Column(1)))
    }

    @Test
    fun `DOWN offset moves one row down`() {
        assertThat(Direction.DOWN.offset).isEqualTo(Position(Row(1), Column(0)))
    }
}
