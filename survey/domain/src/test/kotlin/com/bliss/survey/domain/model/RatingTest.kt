package com.bliss.survey.domain.model

import assertk.assertFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RatingTest {
    private val itemId = ItemId(UUID.randomUUID())
    private val userId = UserId(UUID.randomUUID())
    private val now = Instant.now()

    @Test
    fun `auth rating with user id is valid`() {
        Rating(
            id = RatingId(UUID.randomUUID()),
            itemId = itemId,
            userId = userId,
            submittedAs = SubmittedAs.AUTH,
            qualite = 3,
            difficulte = 3,
            flag = null,
            proposedItemId = null,
            latencyMs = 1000,
            createdAt = now,
        )
    }

    @Test
    fun `anon rating must have null user id`() {
        assertFailure {
            Rating(
                id = RatingId(UUID.randomUUID()),
                itemId = itemId,
                userId = userId, // <-- not null
                submittedAs = SubmittedAs.ANON,
                qualite = 3,
                difficulte = 3,
                flag = null,
                proposedItemId = null,
                latencyMs = 1000,
                createdAt = now,
            )
        }.isInstanceOf(IllegalArgumentException::class).messageContains("anon")
    }

    @Test
    fun `anon rating must have null proposed item id`() {
        val proposed = ItemId(UUID.randomUUID())
        assertFailure {
            Rating(
                id = RatingId(UUID.randomUUID()),
                itemId = itemId,
                userId = null,
                submittedAs = SubmittedAs.ANON,
                qualite = 3,
                difficulte = 3,
                flag = null,
                proposedItemId = proposed,
                latencyMs = 1000,
                createdAt = now,
            )
        }.isInstanceOf(IllegalArgumentException::class).messageContains("anon")
    }

    @Test
    fun `qualite must be in 1-5`() {
        assertFailure {
            Rating(
                id = RatingId(UUID.randomUUID()),
                itemId = itemId,
                userId = userId,
                submittedAs = SubmittedAs.AUTH,
                qualite = 0,
                difficulte = 3,
                flag = null,
                proposedItemId = null,
                latencyMs = 1000,
                createdAt = now,
            )
        }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `difficulte must be in 1-5`() {
        assertFailure {
            Rating(
                id = RatingId(UUID.randomUUID()),
                itemId = itemId,
                userId = userId,
                submittedAs = SubmittedAs.AUTH,
                qualite = 3,
                difficulte = 9,
                flag = null,
                proposedItemId = null,
                latencyMs = 1000,
                createdAt = now,
            )
        }.isInstanceOf(IllegalArgumentException::class)
    }
}
