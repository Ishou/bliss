package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.routing.StratifiedSampler
import com.bliss.survey.domain.routing.TierWeights
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

class GetNextItemUseCaseTest {
    private fun item(
        tier: Tier,
        motSuffix: String,
    ): SurveyItem =
        SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = "MOT$motSuffix",
            definition = "definition $motSuffix",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.AUTRE,
            style = Style.DEFINITION_DIRECTE,
            forceClaimed = 3,
            longueur = 3,
            source = Source.SYNTHETIC_V1,
            sourceBatch = "test",
            tier = tier,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = Instant.now(),
        )

    @Test
    fun `returns a candidate when at least one tier has unrated items`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            val mid = item(Tier.MID, "a")
            repo.insert(mid)
            val uc =
                GetNextItemUseCase(
                    itemRepo = repo,
                    sampler = StratifiedSampler(TierWeights.DEFAULT),
                    randomFactory = { Random(42L) },
                )
            val pick = uc.execute(forUser = null, locallyExcluded = emptySet())
            assertThat(pick).isNotNull()
        }

    @Test
    fun `respects locally excluded set`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            val mid = item(Tier.MID, "b")
            repo.insert(mid)
            val uc =
                GetNextItemUseCase(
                    itemRepo = repo,
                    sampler = StratifiedSampler(TierWeights.DEFAULT),
                    randomFactory = { Random(42L) },
                )
            val pick = uc.execute(forUser = null, locallyExcluded = setOf(mid.id))
            assertThat(pick).isNull()
        }

    @Test
    fun `picks from the only populated tier even when sampler weights favour empty ones`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            repeat(10) { i -> repo.insert(item(Tier.MID, "mid-$i")) }
            val uc =
                GetNextItemUseCase(
                    itemRepo = repo,
                    sampler = StratifiedSampler(TierWeights.DEFAULT),
                    randomFactory = { Random(0L) },
                )
            repeat(8) {
                val pick = uc.execute(forUser = null, locallyExcluded = emptySet())
                assertThat(pick).isNotNull()
            }
        }

    @Test
    fun `returns null when all tiers exhausted`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            val uc =
                GetNextItemUseCase(
                    itemRepo = repo,
                    sampler = StratifiedSampler(TierWeights.DEFAULT),
                    randomFactory = { Random(42L) },
                )
            val pick = uc.execute(forUser = null, locallyExcluded = emptySet())
            assertThat(pick).isEqualTo(null)
        }
}
