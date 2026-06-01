package com.bliss.survey.domain.model

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import org.junit.jupiter.api.Test
import java.time.Instant

class WordMetaTest {
    private fun base() =
        WordMeta(
            mot = "chat",
            subTags = listOf("felin", "domestique"),
            senseInventory = listOf("animal félin", "conversation digitale"),
            updatedAt = Instant.now(),
        )

    @Test
    fun `valid meta constructs`() {
        base()
    }

    @Test
    fun `empty arrays are valid`() {
        assertThat(
            WordMeta("chat", emptyList(), emptyList(), Instant.EPOCH).subTags,
        ).isEqualTo(emptyList())
    }

    @Test
    fun `mot must be non-blank`() {
        assertFailure { base().copy(mot = "") }
            .isInstanceOf(IllegalArgumentException::class)
            .messageContains("non-blank")
        assertFailure { base().copy(mot = "  ") }
            .isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `sub-tags must be non-blank`() {
        assertFailure { base().copy(subTags = listOf("felin", "")) }
            .isInstanceOf(IllegalArgumentException::class)
            .messageContains("subTags")
    }

    @Test
    fun `sense inventory entries must be non-blank`() {
        assertFailure { base().copy(senseInventory = listOf("", "x")) }
            .isInstanceOf(IllegalArgumentException::class)
            .messageContains("senseInventory")
    }

    @Test
    fun `sub-tag length capped`() {
        val tooLong = "a".repeat(WordMeta.MAX_SUB_TAG_LENGTH + 1)
        assertFailure { base().copy(subTags = listOf(tooLong)) }
            .isInstanceOf(IllegalArgumentException::class)
            .messageContains("subTags entries bounded")
    }

    @Test
    fun `sense length capped`() {
        val tooLong = "a".repeat(WordMeta.MAX_SENSE_LENGTH + 1)
        assertFailure { base().copy(senseInventory = listOf(tooLong)) }
            .isInstanceOf(IllegalArgumentException::class)
            .messageContains("senseInventory entries bounded")
    }
}
