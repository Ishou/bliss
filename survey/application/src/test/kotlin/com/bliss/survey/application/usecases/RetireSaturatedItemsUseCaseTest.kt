package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.routing.KCoveragePolicy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RetireSaturatedItemsUseCaseTest {
    private val now = Instant.parse("2026-05-25T12:00:00Z")
    private val clock = Clock { now }

    private fun item(): SurveyItem =
        SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = "MOT",
            definition = "def",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.AUTRE,
            style = Style.DEFINITION_DIRECTE,
            forceClaimed = 3,
            longueur = 3,
            source = Source.CURATED_V1,
            sourceBatch = "b",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = now,
        )

    @Test
    fun `retires every saturated id reported by the repo`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            val a = item()
            val b = item()
            repo.insert(a)
            repo.insert(b)
            repo.saturated = listOf(a.id, b.id)
            val uc = RetireSaturatedItemsUseCase(repo, KCoveragePolicy.DEFAULT, clock)
            val n = uc.execute()
            assertThat(n).isEqualTo(2)
            assertThat(repo.retired).isEqualTo(setOf(a.id, b.id))
        }

    @Test
    fun `idempotent when nothing is saturated`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            val uc = RetireSaturatedItemsUseCase(repo, KCoveragePolicy.DEFAULT, clock)
            val n = uc.execute()
            assertThat(n).isEqualTo(0)
        }
}
