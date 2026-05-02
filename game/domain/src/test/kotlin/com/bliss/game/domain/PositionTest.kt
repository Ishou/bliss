package com.bliss.game.domain

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import org.junit.jupiter.api.Test

class PositionTest {
    @Test
    fun `Position holds row and column`() {
        val p = Position(2, 5)
        assertThat(p.row).isEqualTo(2)
        assertThat(p.column).isEqualTo(5)
    }

    @Test
    fun `Position rejects negative row`() {
        assertFailure { Position(-1, 0) }.messageContains("row")
    }

    @Test
    fun `Position rejects negative column`() {
        assertFailure { Position(0, -1) }.messageContains("column")
    }

    @Test
    fun `GridConfig accepts dimensions in 5 to 15`() {
        assertThat(GridConfig(5, 5).width).isEqualTo(5)
        assertThat(GridConfig(15, 15).height).isEqualTo(15)
        assertThat(GridConfig(7, 11)).isEqualTo(GridConfig(7, 11))
    }

    @Test
    fun `GridConfig accepts the boundary 6 and 14`() {
        // Mutation guard: catches `<=` vs `<` mutations on the boundary.
        assertThat(GridConfig(6, 14).width).isEqualTo(6)
    }

    @Test
    fun `GridConfig rejects width below 5`() {
        assertFailure { GridConfig(4, 5) }.messageContains("width")
    }

    @Test
    fun `GridConfig rejects width above 15`() {
        assertFailure { GridConfig(16, 5) }.messageContains("width")
    }

    @Test
    fun `GridConfig rejects height below 5`() {
        assertFailure { GridConfig(5, 4) }.messageContains("height")
    }

    @Test
    fun `GridConfig rejects height above 15`() {
        assertFailure { GridConfig(5, 16) }.messageContains("height")
    }
}
