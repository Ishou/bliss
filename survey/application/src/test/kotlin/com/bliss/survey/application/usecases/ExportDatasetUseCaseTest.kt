package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.startsWith
import com.bliss.survey.application.csv.StyleGuideCsvWriter
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.RatingAggregate
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

class ExportDatasetUseCaseTest {
    private val now = Instant.parse("2026-05-25T12:00:00Z")

    @Test
    fun `includes items meeting minRatings threshold with meta`() =
        runTest {
            val items = InMemorySurveyItemRepository()
            val ratings = InMemoryRatingRepository()
            val item =
                SurveyItem(
                    id = ItemId(UUID.randomUUID()),
                    mot = "POMME",
                    definition = "Fruit",
                    pos = Pos.NOM_COMMUN,
                    categorie = Categorie.ALIMENTS,
                    style = Style.DEFINITION_DIRECTE,
                    forceClaimed = 3,
                    longueur = 5,
                    source = Source.CURATED_V1,
                    sourceBatch = "batch1",
                    tier = Tier.MID,
                    isCalibration = false,
                    expected = null,
                    retiredAt = null,
                    createdAt = now,
                )
            items.insert(item)
            ratings.aggregateOverride =
                listOf(
                    RatingAggregate(
                        itemId = item.id,
                        qualiteAuthSum = 10,
                        qualiteAuthN = 3,
                        qualiteAnonSum = 4,
                        qualiteAnonN = 2,
                        difficulteAuthSum = 8,
                        difficulteAuthN = 3,
                        difficulteAnonSum = 4,
                        difficulteAnonN = 2,
                        flagCount = 0,
                        qualiteSquaredAuthSum = 36,
                        qualiteSquaredAnonSum = 8,
                    ),
                )
            val uc = ExportDatasetUseCase(items, ratings, StyleGuideCsvWriter(), Clock { now })
            val csv = uc.execute(minRatings = 3, since = null, authWeight = 2.0, anonWeight = 1.0)
            assertThat(csv).startsWith("mot;definition;pos;categorie;style;force;longueur;source;meta")
            assertThat(csv).contains("POMME")
            assertThat(csv).contains("qualite_mean")
        }

    @Test
    fun `skips items below minRatings`() =
        runTest {
            val items = InMemorySurveyItemRepository()
            val ratings = InMemoryRatingRepository()
            val item =
                SurveyItem(
                    id = ItemId(UUID.randomUUID()),
                    mot = "POIRE",
                    definition = "Fruit du poirier",
                    pos = Pos.NOM_COMMUN,
                    categorie = Categorie.ALIMENTS,
                    style = Style.DEFINITION_DIRECTE,
                    forceClaimed = 3,
                    longueur = 5,
                    source = Source.CURATED_V1,
                    sourceBatch = "b",
                    tier = Tier.MID,
                    isCalibration = false,
                    expected = null,
                    retiredAt = null,
                    createdAt = now,
                )
            items.insert(item)
            ratings.aggregateOverride =
                listOf(
                    RatingAggregate(
                        itemId = item.id,
                        qualiteAuthSum = 4,
                        qualiteAuthN = 1,
                        qualiteAnonSum = 0,
                        qualiteAnonN = 0,
                        difficulteAuthSum = 3,
                        difficulteAuthN = 1,
                        difficulteAnonSum = 0,
                        difficulteAnonN = 0,
                        flagCount = 0,
                        qualiteSquaredAuthSum = 16,
                        qualiteSquaredAnonSum = 0,
                    ),
                )
            val uc = ExportDatasetUseCase(items, ratings, StyleGuideCsvWriter(), Clock { now })
            val csv = uc.execute(minRatings = 3, since = null, authWeight = 1.0, anonWeight = 1.0)
            // Only the header remains
            assertThat(csv).startsWith("mot;definition;pos;categorie;style;force;longueur;source;meta")
            check(!csv.contains("POIRE")) { "PR-below threshold item should not be exported" }
        }
}
