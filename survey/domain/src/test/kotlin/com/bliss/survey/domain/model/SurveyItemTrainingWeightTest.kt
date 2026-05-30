package com.bliss.survey.domain.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class SurveyItemTrainingWeightTest {
    private fun item(weight: Double) =
        SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = "PAIN",
            definition = "Aliment de boulangerie",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.ALIMENTS,
            style = Style.DEFINITION_DIRECTE,
            forceClaimed = 1,
            longueur = 4,
            source = Source.GOLD,
            sourceBatch = "gold_v1",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = Instant.parse("2026-05-25T12:00:00Z"),
            trainingWeight = weight,
        )

    @Test
    fun `defaults to 1_0 when omitted`() {
        val withoutWeight =
            SurveyItem(
                id = ItemId(UUID.randomUUID()),
                mot = "PAIN",
                definition = "Aliment de boulangerie",
                pos = Pos.NOM_COMMUN,
                categorie = Categorie.ALIMENTS,
                style = Style.DEFINITION_DIRECTE,
                forceClaimed = 1,
                longueur = 4,
                source = Source.GOLD,
                sourceBatch = "gold_v1",
                tier = Tier.MID,
                isCalibration = false,
                expected = null,
                retiredAt = null,
                createdAt = Instant.parse("2026-05-25T12:00:00Z"),
            )
        assertThat(withoutWeight.trainingWeight).isEqualTo(1.0)
    }

    @Test
    fun `rejects zero weight`() {
        assertThrows<IllegalArgumentException> { item(0.0) }
    }

    @Test
    fun `rejects negative weight`() {
        assertThrows<IllegalArgumentException> { item(-1.0) }
    }
}
