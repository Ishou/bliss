package com.bliss.identity.domain.provider

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class SubjectTest {
    @Test
    fun `Subject preserves the raw IdP-issued string`() {
        assertThat(Subject.of("12345-google-sub").value).isEqualTo("12345-google-sub")
    }

    @Test
    fun `Subject rejects blank`() {
        assertFailure { Subject.of("") }
            .isInstanceOf(IllegalArgumentException::class)
        assertFailure { Subject.of("  ") }
            .isInstanceOf(IllegalArgumentException::class)
    }
}
