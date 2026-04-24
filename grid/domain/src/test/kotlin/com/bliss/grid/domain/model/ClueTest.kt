package com.bliss.grid.domain.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class ClueTest {

    @Test
    fun `Clue exposes definition and direction`() {
        val c = Clue("animal", Direction.RIGHT)
        assertThat(c.definition).isEqualTo("animal")
        assertThat(c.direction).isEqualTo(Direction.RIGHT)
    }

    @Test
    fun `Clue equality is structural`() {
        assertThat(Clue("a", Direction.DOWN)).isEqualTo(Clue("a", Direction.DOWN))
    }
}
