package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LubySequenceTest {
    @Test
    fun `first 31 values match textbook sequence`() {
        // Textbook Luby: 1, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8,
        //                1, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8, 16
        val expected =
            listOf(
                1,
                1,
                2,
                1,
                1,
                2,
                4,
                1,
                1,
                2,
                1,
                1,
                2,
                4,
                8,
                1,
                1,
                2,
                1,
                1,
                2,
                4,
                1,
                1,
                2,
                1,
                1,
                2,
                4,
                8,
                16,
            )
        val actual = (1..31).map { luby(it) }
        assertThat(actual).containsExactly(*expected.toIntArray().toTypedArray())
    }

    @Test
    fun `powers of two at canonical positions`() {
        // 2^(k-1) appears at position 2^k - 1. So 1@1, 2@3, 4@7, 8@15, 16@31, 32@63.
        assertThat(luby(1)).isEqualTo(1)
        assertThat(luby(3)).isEqualTo(2)
        assertThat(luby(7)).isEqualTo(4)
        assertThat(luby(15)).isEqualTo(8)
        assertThat(luby(31)).isEqualTo(16)
        assertThat(luby(63)).isEqualTo(32)
    }

    @Test
    fun `i must be at least 1`() {
        assertThrows<IllegalArgumentException> { luby(0) }
        assertThrows<IllegalArgumentException> { luby(-1) }
    }
}
