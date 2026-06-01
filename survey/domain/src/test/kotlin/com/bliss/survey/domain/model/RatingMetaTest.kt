package com.bliss.survey.domain.model

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RatingMetaTest {
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
    fun `meta defaults are empty`() {
        val r = authBase()
        assertThat(r.targetCategories).isEqualTo(emptyList())
        assertThat(r.targetSense).isEqualTo(null)
        assertThat(r.isMultisense).isFalse()
        assertThat(r.subTags).isEqualTo(emptyList())
    }

    @Test
    fun `auth rating accepts up to MAX target categories`() {
        val full = Categorie.entries.take(Rating.MAX_TARGET_CATEGORIES)
        assertThat(authBase().copy(targetCategories = full).targetCategories.size)
            .isEqualTo(Rating.MAX_TARGET_CATEGORIES)
    }

    @Test
    fun `too many target categories rejected`() {
        val over = (0..Rating.MAX_TARGET_CATEGORIES).map { Categorie.entries[it % Categorie.entries.size] }
        assertFailure { authBase().copy(targetCategories = over) }
            .isInstanceOf(IllegalArgumentException::class)
            .messageContains("targetCategories bounded")
    }

    @Test
    fun `blank target sense rejected`() {
        assertFailure { authBase().copy(targetSense = "  ") }
            .isInstanceOf(IllegalArgumentException::class)
            .messageContains("blank")
    }

    @Test
    fun `oversized target sense rejected`() {
        val tooLong = "a".repeat(Rating.MAX_SENSE_LENGTH + 1)
        assertFailure { authBase().copy(targetSense = tooLong) }
            .isInstanceOf(IllegalArgumentException::class)
            .messageContains("targetSense bounded")
    }

    @Test
    fun `too many sub-tags rejected`() {
        val over = (1..Rating.MAX_SUB_TAGS + 1).map { "tag-$it" }
        assertFailure { authBase().copy(subTags = over) }
            .isInstanceOf(IllegalArgumentException::class)
            .messageContains("subTags bounded")
    }

    @Test
    fun `blank sub-tag rejected`() {
        assertFailure { authBase().copy(subTags = listOf("ok", "")) }
            .isInstanceOf(IllegalArgumentException::class)
            .messageContains("blank")
    }

    @Test
    fun `oversized sub-tag rejected`() {
        val tooLong = "a".repeat(Rating.MAX_SUB_TAG_LENGTH + 1)
        assertFailure { authBase().copy(subTags = listOf(tooLong)) }
            .isInstanceOf(IllegalArgumentException::class)
            .messageContains("entries bounded")
    }

    @Test
    fun `anon rating may not carry meta`() {
        assertFailure {
            authBase().copy(
                userId = null,
                submittedAs = SubmittedAs.ANON,
                proposedItemId = null,
                subTags = listOf("felin"),
            )
        }.isInstanceOf(IllegalArgumentException::class)
            .messageContains("anon rating must carry no meta")
    }
}
