package com.bliss.identity.infrastructure.id

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEqualTo
import org.junit.jupiter.api.Test

class UuidV7IdGeneratorTest {
    @Test
    fun `newUserId returns a UUIDv7`() {
        val id = UuidV7IdGenerator().newUserId()
        assertThat(id.value.version()).isEqualTo(7)
    }

    @Test
    fun `newSessionId returns a UUIDv7`() {
        val id = UuidV7IdGenerator().newSessionId()
        assertThat(id.value.version()).isEqualTo(7)
    }

    @Test
    fun `newAuthAttemptId returns a UUIDv7`() {
        val id = UuidV7IdGenerator().newAuthAttemptId()
        assertThat(id.value.version()).isEqualTo(7)
    }

    @Test
    fun `two ids from the same generator differ`() {
        val gen = UuidV7IdGenerator()
        assertThat(gen.newUserId().value).isNotEqualTo(gen.newUserId().value)
    }

    @Test
    fun `later id sorts greater than earlier id`() {
        val gen = UuidV7IdGenerator()
        val first = gen.newUserId().value
        Thread.sleep(2) // UUIDv7's millisecond timestamp grants ordering only across ms boundaries.
        val second = gen.newUserId().value
        assertThat(second).isGreaterThan(first)
    }
}
