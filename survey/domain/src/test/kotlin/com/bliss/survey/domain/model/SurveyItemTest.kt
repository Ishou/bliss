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

    @Test
    fun `CalibrationAnswer valid construction succeeds`() {
        CalibrationAnswer(
            expectedQualiteMin = 2,
            expectedQualiteMax = 4,
            expectedDifficulteMin = 1,
            expectedDifficulteMax = 5,
        )
    }

    @Test
    fun `CalibrationAnswer qualiteMin greater than qualiteMax throws`() {
        assertFailure {
            CalibrationAnswer(
                expectedQualiteMin = 4,
                expectedQualiteMax = 2,
                expectedDifficulteMin = 1,
                expectedDifficulteMax = 5,
            )
        }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `CalibrationAnswer difficulteMin greater than difficulteMax throws`() {
        assertFailure {
            CalibrationAnswer(
                expectedQualiteMin = 1,
                expectedQualiteMax = 5,
                expectedDifficulteMin = 5,
                expectedDifficulteMax = 3,
            )
        }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `CalibrationAnswer qualite value out of 1 to 5 range throws`() {
        assertFailure {
            CalibrationAnswer(
                expectedQualiteMin = 0,
                expectedQualiteMax = 3,
                expectedDifficulteMin = 1,
                expectedDifficulteMax = 5,
            )
        }.isInstanceOf(IllegalArgumentException::class)
        assertFailure {
            CalibrationAnswer(
                expectedQualiteMin = 1,
                expectedQualiteMax = 6,
                expectedDifficulteMin = 1,
                expectedDifficulteMax = 5,
            )
        }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `CalibrationAnswer difficulte value out of 1 to 5 range throws`() {
        assertFailure {
            CalibrationAnswer(
                expectedQualiteMin = 1,
                expectedQualiteMax = 5,
                expectedDifficulteMin = 0,
                expectedDifficulteMax = 5,
            )
        }.isInstanceOf(IllegalArgumentException::class)
        assertFailure {
            CalibrationAnswer(
                expectedQualiteMin = 1,
                expectedQualiteMax = 5,
                expectedDifficulteMin = 1,
                expectedDifficulteMax = 6,
            )
        }.isInstanceOf(IllegalArgumentException::class)
    }
}
