package com.bliss.survey.domain.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CampaignTest {
    private val openedAt = Instant.parse("2026-05-30T10:00:00Z")
    private val closedAt = Instant.parse("2026-05-30T12:00:00Z")

    @Test
    fun `isOpen is true when closedAt is null`() {
        val c =
            Campaign(
                id = CampaignId(UUID.randomUUID()),
                batchLabel = "round-7",
                openedAt = openedAt,
                closedAt = null,
            )
        assertThat(c.isOpen).isTrue()
        assertThat(c.isClosed).isFalse()
    }

    @Test
    fun `isClosed is true when closedAt is non-null`() {
        val c =
            Campaign(
                id = CampaignId(UUID.randomUUID()),
                batchLabel = "round-7",
                openedAt = openedAt,
                closedAt = closedAt,
            )
        assertThat(c.isOpen).isFalse()
        assertThat(c.isClosed).isTrue()
    }

    @Test
    fun `batchLabel must not be blank`() {
        try {
            Campaign(
                id = CampaignId(UUID.randomUUID()),
                batchLabel = "",
                openedAt = openedAt,
                closedAt = null,
            )
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).isEqualTo("batchLabel must not be blank")
        }
    }
}
