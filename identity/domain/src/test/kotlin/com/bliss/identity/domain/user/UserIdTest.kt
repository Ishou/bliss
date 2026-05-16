package com.bliss.identity.domain.user

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class UserIdTest {
    @Test
    fun `parse round-trips a valid UUID`() {
        val uuid = UUID.fromString("01890c5e-0000-7000-8000-000000000000")
        assertThat(UserId.parse(uuid.toString()).value).isEqualTo(uuid)
    }

    @Test
    fun `parse rejects a non-UUID string`() {
        assertFailure { UserId.parse("not-a-uuid") }
            .isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `toString returns the canonical UUID form`() {
        val uuid = UUID.fromString("01890c5e-0000-7000-8000-000000000000")
        assertThat(UserId(uuid).toString()).isEqualTo("01890c5e-0000-7000-8000-000000000000")
    }
}
