package com.bliss.survey.application

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import org.junit.jupiter.api.Test

class UndoTokenHashTest {
    @Test
    fun `is deterministic and 32 bytes`() {
        val a = sha256("token-abc")
        val b = sha256("token-abc")
        assertThat(a.toList()).isEqualTo(b.toList())
        assertThat(a).hasSize(32)
    }

    @Test
    fun `differs for different tokens`() {
        assertThat(sha256("x").toList()).isNotEqualTo(sha256("y").toList())
    }
}
