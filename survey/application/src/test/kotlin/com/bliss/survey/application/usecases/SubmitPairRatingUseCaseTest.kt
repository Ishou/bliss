package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.IdGenerator
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.PairVerdict
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.PreferenceVerdict
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SubmittedAs
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SubmitPairRatingUseCaseTest {
    private val fixedNow: Instant = Instant.parse("2026-05-25T12:00:00Z")
    private val clock = Clock { fixedNow }

    private fun idGen() =
        object : IdGenerator {
            private var counter = 0L

            override fun next(): UUID = UUID(0L, counter++)
        }

    private fun item(
        mot: String,
        definitionSuffix: String,
    ): SurveyItem =
        SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = mot,
            definition = "Definition $definitionSuffix",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.AUTRE,
            style = Style.DEFINITION_DIRECTE,
            forceClaimed = 3,
            longueur = mot.length,
            source = Source.SYNTHETIC_V1,
            sourceBatch = "test",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = fixedNow,
        )

    private fun wire(): Setup {
        val items = InMemorySurveyItemRepository()
        val ratings = InMemoryRatingRepository()
        val pairRatings = InMemoryPairRatingRepository()
        val progress = InMemoryUserProgressRepository()
        val uc =
            SubmitPairRatingUseCase(
                items = items,
                ratings = ratings,
                pairRatings = pairRatings,
                progress = progress,
                ids = idGen(),
                clock = clock,
            )
        return Setup(uc, items, ratings, pairRatings, progress)
    }

    private data class Setup(
        val uc: SubmitPairRatingUseCase,
        val items: InMemorySurveyItemRepository,
        val ratings: InMemoryRatingRepository,
        val pairRatings: InMemoryPairRatingRepository,
        val progress: InMemoryUserProgressRepository,
    )

    @Test
    fun `LEFT_WINS persists one pair_ratings row`() =
        runTest {
            val (uc, items, ratings, pairRatings, _) = wire()
            val a = item("POMME", "a")
            val b = item("POMME", "b")
            items.insert(a)
            items.insert(b)
            val result =
                uc.execute(
                    SubmitPairRatingCommand(
                        leftItemId = a.id,
                        rightItemId = b.id,
                        userId = UserId(UUID.randomUUID()),
                        verdict = PairVerdict.LEFT_WINS,
                        difficulte = 3,
                        latencyMs = 1500,
                    ),
                )
            assertThat(result).isEqualTo(SubmitPairRatingResult.Recorded)
            assertThat(pairRatings.rows).hasSize(1)
            assertThat(pairRatings.rows.single().verdict).isEqualTo(PreferenceVerdict.LEFT_WINS)
            assertThat(ratings.ratings).hasSize(0)
        }

    @Test
    fun `RIGHT_WINS persists one pair_ratings row with right preference`() =
        runTest {
            val (uc, items, _, pairRatings, _) = wire()
            val a = item("POMME", "a")
            val b = item("POMME", "b")
            items.insert(a)
            items.insert(b)
            uc.execute(
                SubmitPairRatingCommand(a.id, b.id, null, PairVerdict.RIGHT_WINS, 2, 1100),
            )
            assertThat(pairRatings.rows.single().verdict).isEqualTo(PreferenceVerdict.RIGHT_WINS)
        }

    @Test
    fun `BOTH_GOOD writes two ratings rows with qualite=5`() =
        runTest {
            val (uc, items, ratings, pairRatings, _) = wire()
            val a = item("POMME", "a")
            val b = item("POMME", "b")
            items.insert(a)
            items.insert(b)
            val user = UserId(UUID.randomUUID())
            uc.execute(
                SubmitPairRatingCommand(a.id, b.id, user, PairVerdict.BOTH_GOOD, 3, 1200),
            )
            assertThat(pairRatings.rows).hasSize(0)
            assertThat(ratings.ratings).hasSize(2)
            assertThat(ratings.ratings.map { it.qualite }.toSet()).isEqualTo(setOf(5))
            assertThat(ratings.ratings.map { it.itemId }).containsExactlyInAnyOrder(a.id, b.id)
            assertThat(ratings.ratings.map { it.submittedAs }.toSet()).isEqualTo(setOf(SubmittedAs.AUTH))
        }

    @Test
    fun `BOTH_BAD writes two ratings rows with qualite=1`() =
        runTest {
            val (uc, items, ratings, _, _) = wire()
            val a = item("POMME", "a")
            val b = item("POMME", "b")
            items.insert(a)
            items.insert(b)
            uc.execute(
                SubmitPairRatingCommand(a.id, b.id, null, PairVerdict.BOTH_BAD, 4, 2000),
            )
            assertThat(ratings.ratings).hasSize(2)
            assertThat(ratings.ratings.map { it.qualite }.toSet()).isEqualTo(setOf(1))
            assertThat(ratings.ratings.map { it.submittedAs }.toSet()).isEqualTo(setOf(SubmittedAs.ANON))
        }

    @Test
    fun `SKIP writes nothing`() =
        runTest {
            val (uc, items, ratings, pairRatings, progress) = wire()
            val a = item("POMME", "a")
            val b = item("POMME", "b")
            items.insert(a)
            items.insert(b)
            val result =
                uc.execute(
                    SubmitPairRatingCommand(a.id, b.id, UserId(UUID.randomUUID()), PairVerdict.SKIP, 3, 500),
                )
            assertThat(result).isEqualTo(SubmitPairRatingResult.Skipped)
            assertThat(ratings.ratings).hasSize(0)
            assertThat(pairRatings.rows).hasSize(0)
            assertThat(progress.progress).hasSize(0)
        }

    @Test
    fun `unknown left item returns ItemNotFound`() =
        runTest {
            val (uc, items, _, _, _) = wire()
            val b = item("POMME", "b")
            items.insert(b)
            val result =
                uc.execute(
                    SubmitPairRatingCommand(
                        leftItemId = ItemId(UUID.randomUUID()),
                        rightItemId = b.id,
                        userId = null,
                        verdict = PairVerdict.LEFT_WINS,
                        difficulte = 3,
                        latencyMs = 1000,
                    ),
                )
            assertThat(result).isEqualTo(SubmitPairRatingResult.ItemNotFound)
        }

    @Test
    fun `mismatched mot returns PairMotMismatch`() =
        runTest {
            val (uc, items, _, _, _) = wire()
            val a = item("POMME", "a")
            val b = item("CHIEN", "b")
            items.insert(a)
            items.insert(b)
            val result =
                uc.execute(
                    SubmitPairRatingCommand(a.id, b.id, null, PairVerdict.LEFT_WINS, 3, 1000),
                )
            assertThat(result).isEqualTo(SubmitPairRatingResult.PairMotMismatch)
        }

    @Test
    fun `same item on both sides returns SameItem`() =
        runTest {
            val (uc, items, _, _, _) = wire()
            val a = item("POMME", "a")
            items.insert(a)
            val result =
                uc.execute(
                    SubmitPairRatingCommand(a.id, a.id, null, PairVerdict.LEFT_WINS, 3, 1000),
                )
            assertThat(result).isEqualTo(SubmitPairRatingResult.SameItem)
        }

    @Test
    fun `duplicate auth submission returns AlreadyExists`() =
        runTest {
            val (uc, items, _, _, _) = wire()
            val a = item("POMME", "a")
            val b = item("POMME", "b")
            items.insert(a)
            items.insert(b)
            val user = UserId(UUID.randomUUID())
            uc.execute(SubmitPairRatingCommand(a.id, b.id, user, PairVerdict.LEFT_WINS, 3, 1200))
            val second =
                uc.execute(SubmitPairRatingCommand(b.id, a.id, user, PairVerdict.RIGHT_WINS, 3, 1200))
            assertThat(second).isEqualTo(SubmitPairRatingResult.AlreadyExists)
        }

    @Test
    fun `auth preference increments progress once`() =
        runTest {
            val (uc, items, _, _, progress) = wire()
            val a = item("POMME", "a")
            val b = item("POMME", "b")
            items.insert(a)
            items.insert(b)
            val user = UserId(UUID.randomUUID())
            uc.execute(SubmitPairRatingCommand(a.id, b.id, user, PairVerdict.LEFT_WINS, 3, 1200))
            assertThat(progress.progress[user]?.itemsRated).isEqualTo(1)
        }

    @Test
    fun `auth BOTH_GOOD blocked when caller already rated either side in binary mode`() =
        runTest {
            val (uc, items, ratings, _, _) = wire()
            val a = item("POMME", "a")
            val b = item("POMME", "b")
            items.insert(a)
            items.insert(b)
            val user = UserId(UUID.randomUUID())
            ratings.ratings +=
                com.bliss.survey.domain.model.Rating(
                    id =
                        com.bliss.survey.domain.model
                            .RatingId(UUID.randomUUID()),
                    itemId = a.id,
                    userId = user,
                    submittedAs = SubmittedAs.AUTH,
                    qualite = 4,
                    difficulte = 2,
                    flag = null,
                    proposedItemId = null,
                    latencyMs = 1000,
                    createdAt = fixedNow,
                )
            val result =
                uc.execute(SubmitPairRatingCommand(a.id, b.id, user, PairVerdict.BOTH_GOOD, 3, 1200))
            assertThat(result).isEqualTo(SubmitPairRatingResult.AlreadyExists)
        }
}
