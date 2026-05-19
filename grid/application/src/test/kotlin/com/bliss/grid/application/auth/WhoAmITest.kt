package com.bliss.grid.application.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import java.util.UUID

class WhoAmITest {
    @Test
    fun `WhoAmI carries userId and displayName verbatim`() {
        val id = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b")
        val w = WhoAmI(userId = id, displayName = "Isho")

        assertThat(w.userId).isEqualTo(id)
        assertThat(w.displayName).isEqualTo("Isho")
    }
}
