package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.bliss.survey.application.filters.FilterPipeline
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.IdGenerator
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
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

class SubmitRatingUseCaseTest {
    private val fixedNow: Instant = Instant.parse("2026-05-25T12:00:00Z")
    private val clock = Clock { fixedNow }
    private val idGen =
        object : IdGenerator {
            private var counter = 0L

            override fun next(): UUID = UUID(0L, counter++)
        }

    private fun seedItem(repo: InMemorySurveyItemRepository): SurveyItem {
        val item =
            SurveyItem(
                id = ItemId(UUID.randomUUID()),
                mot = "POMME",
                definition = "Fruit du pommier de la famille des rosacees",
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
                createdAt = fixedNow,
            )
        kotlinx.coroutines.runBlocking { repo.insert(item) }
        return item
    }

    private fun newUseCase() =
        Quad(
            InMemorySurveyItemRepository(),
            InMemoryRatingRepository(),
            InMemoryProposedByRepository(),
            InMemoryUserProgressRepository(),
        ).let { (items, ratings, proposed, progress) ->
            val uc =
                SubmitRatingUseCase(
                    items = items,
                    ratings = ratings,
                    proposedBy = proposed,
                    progress = progress,
                    filters = FilterPipeline.default { _ -> false },
                    ids = idGen,
                    clock = clock,
                )
            Quintet(uc, items, ratings, proposed, progress)
        }

    private data class Quad<A, B, C, D>(
        val a: A,
        val b: B,
        val c: C,
        val d: D,
    )

    private data class Quintet<A, B, C, D, E>(
        val a: A,
        val b: B,
        val c: C,
        val d: D,
        val e: E,
    )

    @Test
    fun `anon happy path stores rating with anon submitter`() =
        runTest {
            val (uc, items, ratings, _, _) = newUseCase()
            val parent = seedItem(items)
            val r =
                uc.execute(
                    SubmitRatingCommand(
                        itemId = parent.id,
                        userId = null,
                        qualite = 4,
                        difficulte = 2,
                        flag = null,
                        correctif = null,
                        latencyMs = 1200,
                    ),
                )
            assertThat(r).isInstanceOf(SubmitRatingResult.Accepted::class)
            check(r is SubmitRatingResult.Accepted)
            assertThat(r.rating.submittedAs).isEqualTo(SubmittedAs.ANON)
            assertThat(ratings.ratings.size).isEqualTo(1)
        }

    @Test
    fun `auth duplicate returns AlreadyExists`() =
        runTest {
            val (uc, items, ratings, _, _) = newUseCase()
            val parent = seedItem(items)
            val userId = UserId(UUID.randomUUID())
            uc.execute(
                SubmitRatingCommand(parent.id, userId, 5, 3, null, null, 1000),
            )
            val r = uc.execute(SubmitRatingCommand(parent.id, userId, 4, 4, null, null, 900))
            assertThat(r).isInstanceOf(SubmitRatingResult.AlreadyExists::class)
            assertThat(ratings.ratings.size).isEqualTo(1)
        }

    @Test
    fun `anon plus correctif is forbidden`() =
        runTest {
            val (uc, items, _, _, _) = newUseCase()
            val parent = seedItem(items)
            val r =
                uc.execute(
                    SubmitRatingCommand(
                        itemId = parent.id,
                        userId = null,
                        qualite = 3,
                        difficulte = 3,
                        flag = null,
                        correctif = "Une meilleure definition" to Style.DEFINITION_DIRECTE,
                        latencyMs = 1000,
                    ),
                )
            assertThat(r).isEqualTo(SubmitRatingResult.AnonCorrectifForbidden)
        }

    @Test
    fun `item not found returns ItemNotFound`() =
        runTest {
            val (uc, _, _, _, _) = newUseCase()
            val r =
                uc.execute(
                    SubmitRatingCommand(
                        itemId = ItemId(UUID.randomUUID()),
                        userId = null,
                        qualite = 3,
                        difficulte = 3,
                        flag = null,
                        correctif = null,
                        latencyMs = 1000,
                    ),
                )
            assertThat(r).isEqualTo(SubmitRatingResult.ItemNotFound)
        }

    @Test
    fun `correctif rejected by filter surfaces filter id`() =
        runTest {
            val (uc, items, _, _, _) = newUseCase()
            val parent = seedItem(items)
            val userId = UserId(UUID.randomUUID())
            val r =
                uc.execute(
                    SubmitRatingCommand(
                        itemId = parent.id,
                        userId = userId,
                        qualite = 3,
                        difficulte = 3,
                        flag = null,
                        // Filter4 (stereotypes) catches the "Quelqu'un qui ..." prefix
                        correctif = "Quelqu'un qui mange un fruit" to Style.PERIPHRASE,
                        latencyMs = 1000,
                    ),
                )
            assertThat(r).isInstanceOf(SubmitRatingResult.CorrectifRejected::class)
            check(r is SubmitRatingResult.CorrectifRejected)
            assertThat(r.filterId).isEqualTo(4)
        }

    @Test
    fun `correctif insert auto-rates the proposed item GOOD by the same user`() =
        runTest {
            val (uc, items, ratings, proposed, _) = newUseCase()
            val parent = seedItem(items)
            val userId = UserId(UUID.randomUUID())
            val r =
                uc.execute(
                    SubmitRatingCommand(
                        itemId = parent.id,
                        userId = userId,
                        qualite = 3,
                        difficulte = 3,
                        flag = null,
                        correctif = "Fruit defendu d'Eve" to Style.PERIPHRASE,
                        latencyMs = 1500,
                    ),
                )
            assertThat(r).isInstanceOf(SubmitRatingResult.Accepted::class)
            assertThat(ratings.ratings.size).isEqualTo(2)
            val proposedItem = items.items.values.single { it.source == Source.RATER_PROPOSED }
            assertThat(proposed.links.single().itemId).isEqualTo(proposedItem.id)
            val onProposed = ratings.ratings.single { it.itemId == proposedItem.id }
            assertThat(onProposed.userId).isEqualTo(userId)
            assertThat(onProposed.qualite).isEqualTo(5)
            assertThat(onProposed.submittedAs).isEqualTo(SubmittedAs.AUTH)
            assertThat(onProposed.proposedItemId).isEqualTo(null)
        }

    @Test
    fun `auth happy path increments user progress`() =
        runTest {
            val (uc, items, _, _, progress) = newUseCase()
            val parent = seedItem(items)
            val userId = UserId(UUID.randomUUID())
            uc.execute(SubmitRatingCommand(parent.id, userId, 5, 3, null, null, 700))
            assertThat(progress.progress[userId]?.itemsRated).isEqualTo(1)
        }
}
