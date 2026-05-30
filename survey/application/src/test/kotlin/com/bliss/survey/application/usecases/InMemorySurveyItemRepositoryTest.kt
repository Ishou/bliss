package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemorySurveyItemRepositoryTest {
    private fun item() =
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

    @Test
    fun `findById returns stamped weight`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            val it = item()
            repo.insert(it)
            repo.updateTrainingWeight(it.id, 3.0)
            assertThat(repo.findById(it.id)?.trainingWeight).isEqualTo(3.0)
        }

    @Test
    fun `findById returns default weight when unstamped`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            val it = item()
            repo.insert(it)
            assertThat(repo.findById(it.id)?.trainingWeight).isEqualTo(1.0)
        }
}
