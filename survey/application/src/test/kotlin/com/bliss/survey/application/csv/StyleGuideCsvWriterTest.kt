package com.bliss.survey.application.csv

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class StyleGuideCsvWriterTest {
    private val writer = StyleGuideCsvWriter()

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
    fun `header includes training_weight before meta`() {
        assertThat(writer.header())
            .isEqualTo("mot;definition;pos;categorie;style;force;longueur;source;training_weight;meta")
    }

    @Test
    fun `row emits weight between source and meta`() {
        val row = writer.toRow(item(3.0), meta = emptyMap())
        assertThat(row).isEqualTo("PAIN;Aliment de boulangerie;nom_commun;aliments;définition_directe;1;4;gold;3.0;")
    }

    @Test
    fun `row emits default weight as 1_0`() {
        val row = writer.toRow(item(1.0), meta = emptyMap())
        assertThat(row).isEqualTo("PAIN;Aliment de boulangerie;nom_commun;aliments;définition_directe;1;4;gold;1.0;")
    }
}
