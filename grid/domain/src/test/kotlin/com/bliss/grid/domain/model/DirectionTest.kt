package com.bliss.grid.domain.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class DirectionTest {
    @Test
    fun `RIGHT starts one column right, steps right`() {
        assertThat(Direction.RIGHT.startOffset).isEqualTo(Position(Row(0), Column(1)))
        assertThat(Direction.RIGHT.step).isEqualTo(Position(Row(0), Column(1)))
        assertThat(Direction.RIGHT.axis).isEqualTo(WordAxis.HORIZONTAL)
    }

    @Test
    fun `DOWN starts one row down, steps down`() {
        assertThat(Direction.DOWN.startOffset).isEqualTo(Position(Row(1), Column(0)))
        assertThat(Direction.DOWN.step).isEqualTo(Position(Row(1), Column(0)))
        assertThat(Direction.DOWN.axis).isEqualTo(WordAxis.VERTICAL)
    }

}
