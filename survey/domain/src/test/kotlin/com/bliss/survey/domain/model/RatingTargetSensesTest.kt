package com.bliss.survey.domain.model

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RatingTargetSensesTest {
    private fun authBase() =
        Rating(
            id = RatingId(UUID.randomUUID()),
            itemId = ItemId(UUID.randomUUID()),
            userId = UserId(UUID.randomUUID()),
            submittedAs = SubmittedAs.AUTH,
            qualite = 3,
            difficulte = 3,
            flag = null,
            proposedItemId = null,
            latencyMs = 1000,
            createdAt = Instant.now(),
        )

    @Test
    fun `target senses defaults to empty list`() {
        assertThat(authBase().targetSenses).isEqualTo(emptyList())
    }

    @Test
    fun `auth rating accepts up to MAX target senses`() {
        val full = (1..Rating.MAX_TARGET_SENSES).map { "gloss-$it" }
        assertThat(authBase().copy(targetSenses = full).targetSenses.size)
            .isEqualTo(Rating.MAX_TARGET_SENSES)
    }

    @Test
    fun `too many target senses rejected`() {
        val over = (1..Rating.MAX_TARGET_SENSES + 1).map { "gloss-$it" }
        assertFailure { authBase().copy(targetSenses = over) }
            .isInstanceOf(IllegalArgumentException::class)
            .messageContains("targetSenses bounded")
    }

    @Test
    fun `blank target sense rejected`() {
        assertFailure { authBase().copy(targetSenses = listOf("ok", "")) }
            .isInstanceOf(IllegalArgumentException::class)
            .messageContains("blank")
    }

    @Test
    fun `oversized target sense rejected`() {
        val tooLong = "a".repeat(Rating.MAX_TARGET_SENSE_LENGTH + 1)
        assertFailure { authBase().copy(targetSenses = listOf(tooLong)) }
            .isInstanceOf(IllegalArgumentException::class)
            .messageContains("entries bounded")
    }

    @Test
    fun `anon rating may not carry target senses`() {
        assertFailure {
            authBase().copy(
                userId = null,
                submittedAs = SubmittedAs.ANON,
                proposedItemId = null,
                targetSenses = listOf("animal félin"),
            )
        }.isInstanceOf(IllegalArgumentException::class)
            .messageContains("anon rating must have empty target_senses")
    }
}
