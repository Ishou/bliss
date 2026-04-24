package com.bliss.grid.domain.model

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import org.junit.jupiter.api.Test

class PositionTest {

    @Test
    fun `Row holds an int value`() {
        assertThat(Row(3).value).isEqualTo(3)
    }

    @Test
    fun `Row rejects negative values`() {
        assertFailure { Row(-1) }.messageContains("Row")
    }

    @Test
    fun `Column holds an int value`() {
        assertThat(Column(7).value).isEqualTo(7)
    }

    @Test
    fun `Column rejects negative values`() {
        assertFailure { Column(-1) }.messageContains("Column")
    }

    @Test
    fun `Position is a row column pair`() {
        val p = Position(Row(2), Column(5))
        assertThat(p.row).isEqualTo(Row(2))
        assertThat(p.column).isEqualTo(Column(5))
    }

    @Test
    fun `Position equality is structural`() {
        assertThat(Position(Row(1), Column(2))).isEqualTo(Position(Row(1), Column(2)))
    }
}
