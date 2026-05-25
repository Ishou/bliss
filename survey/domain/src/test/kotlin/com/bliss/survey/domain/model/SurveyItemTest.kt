package com.bliss.survey.domain.model

import assertk.assertFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SurveyItemTest {
    private fun base() =
        SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = "POULE",
            definition = "Femelle du coq",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.ANIMALS,
            style = Style.PERIPHRASE,
            forceClaimed = 2,
            longueur = 5,
            source = Source.SYNTHETIC_V1,
            sourceBatch = "iter18_20260525",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = Instant.now(),
        )

    @Test
    fun `valid item constructs`() {
        base() // throws if invariant fails
    }

    @Test
    fun `force_claimed must be in 1-5`() {
        assertFailure { base().copy(forceClaimed = 0) }
            .isInstanceOf(IllegalArgumentException::class)
            .messageContains("force")
        assertFailure { base().copy(forceClaimed = 6) }
            .isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `mot must not be blank`() {
        assertFailure { base().copy(mot = "  ") }
            .isInstanceOf(IllegalArgumentException::class)
            .messageContains("mot")
    }

    @Test
    fun `definition must not be blank`() {
        assertFailure { base().copy(definition = "") }
            .isInstanceOf(IllegalArgumentException::class)
            .messageContains("definition")
    }

    @Test
    fun `longueur must be positive`() {
        assertFailure { base().copy(longueur = 0) }
            .isInstanceOf(IllegalArgumentException::class)
    }
}
