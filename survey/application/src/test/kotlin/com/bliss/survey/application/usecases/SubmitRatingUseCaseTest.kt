package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import com.bliss.survey.application.filters.FilterPipeline
import com.bliss.survey.application.ports.CampaignRepository
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.IdGenerator
import com.bliss.survey.application.ports.MaintainerRole
import com.bliss.survey.application.ports.TokenGenerator
import com.bliss.survey.domain.model.Campaign
import com.bliss.survey.domain.model.CampaignId
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SubmittedAs
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.weight.GoldWindowPolicy
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

    private val openCampaign =
        Campaign(
            id = CampaignId(UUID(0L, 1234L)),
            batchLabel = "round-7",
            openedAt = fixedNow.minusSeconds(3600),
            closedAt = null,
        )

    private val openCampaignRepo =
        object : CampaignRepository {
            override suspend fun findOpen(): Campaign = openCampaign

            override suspend fun findCurrent(): Campaign = openCampaign

            override suspend fun findById(id: CampaignId): Campaign = openCampaign
        }

    private val goldPolicy = GoldWindowPolicy(Instant.parse("2026-05-30T00:00:00Z"), 3.0)

    private fun newUseCase(
        clock: Clock = this.clock,
        roles: InMemoryMaintainerRoleRepository = InMemoryMaintainerRoleRepository(),
        wordMeta: InMemoryWordMetaRepository = InMemoryWordMetaRepository(),
    ) = Quad(
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
                campaigns = openCampaignRepo,
                recompute = RecomputeTrainingWeightUseCase(roles, items, goldPolicy),
                actions = InMemoryActionLogRepository(),
                tokens = TokenGenerator { "fixed-token" },
                tx = passThroughTransactionManager,
                wordMeta = wordMeta,
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
                        correctif = CorrectifInput("Une meilleure definition", Style.DEFINITION_DIRECTE, null),
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
    fun `anon plus target senses is forbidden`() =
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
                        correctif = null,
                        latencyMs = 1000,
                        targetSenses = listOf("animal félin"),
                    ),
                )
            assertThat(r).isEqualTo(SubmitRatingResult.AnonTargetSensesForbidden)
        }

    @Test
    fun `auth rating with target senses populates sense inventory`() =
        runTest {
            val wordMeta = InMemoryWordMetaRepository()
            val (uc, items, _, _, _) = newUseCase(wordMeta = wordMeta)
            val parent = seedItem(items)
            uc.execute(
                SubmitRatingCommand(
                    itemId = parent.id,
                    userId = UserId(UUID.fromString("11111111-1111-7111-8111-111111111111")),
                    qualite = 4,
                    difficulte = 2,
                    flag = null,
                    correctif = null,
                    latencyMs = 1200,
                    targetSenses = listOf("fruit du pommier", "objet rond"),
                ),
            )
            assertThat(wordMeta.rows[parent.mot]!!.senseInventory)
                .isEqualTo(listOf("fruit du pommier", "objet rond"))
        }

    @Test
    fun `auth rating with overlapping senses dedups by normalized form and preserves first seen`() =
        runTest {
            val wordMeta = InMemoryWordMetaRepository()
            wordMeta.save(
                com.bliss.survey.domain.model.WordMeta(
                    mot = "POMME",
                    subTags = emptyList(),
                    senseInventory = listOf("Fruit du pommier"),
                    updatedAt = fixedNow,
                ),
            )
            val (uc, items, _, _, _) = newUseCase(wordMeta = wordMeta)
            val parent = seedItem(items)
            uc.execute(
                SubmitRatingCommand(
                    itemId = parent.id,
                    userId = UserId(UUID.fromString("22222222-2222-7222-8222-222222222222")),
                    qualite = 4,
                    difficulte = 2,
                    flag = null,
                    correctif = null,
                    latencyMs = 1200,
                    targetSenses = listOf("fruit du pommier", "objet rond"),
                ),
            )
            // Incoming-first ordering: new gloss kept as first-seen spelling, existing dropped as duplicate.
            assertThat(wordMeta.rows[parent.mot]!!.senseInventory)
                .isEqualTo(listOf("fruit du pommier", "objet rond"))
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
                        correctif = CorrectifInput("Quelqu'un qui mange un fruit", Style.PERIPHRASE, null),
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
                        correctif = CorrectifInput("Fruit defendu d'Eve", Style.PERIPHRASE, null),
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
    fun `correctif duplicating an existing clue reuses its id so the auto-GOOD rating never dangles`() =
        runTest {
            val (uc, items, ratings, proposed, _) = newUseCase()
            val parent = seedItem(items)
            val userId = UserId(UUID.randomUUID())
            val duplicateText = "Fruit defendu d'Eve"
            // An item with the proposed (mot, definition) already exists in the corpus.
            val existing =
                SurveyItem(
                    id = ItemId(UUID.randomUUID()),
                    mot = parent.mot,
                    definition = duplicateText,
                    pos = parent.pos,
                    categorie = parent.categorie,
                    style = Style.PERIPHRASE,
                    forceClaimed = 3,
                    longueur = parent.mot.length,
                    source = Source.RATER_PROPOSED,
                    sourceBatch = "rater_2026-05",
                    tier = Tier.MID,
                    isCalibration = false,
                    expected = null,
                    retiredAt = null,
                    createdAt = fixedNow,
                )
            items.insert(existing)
            val r =
                uc.execute(
                    SubmitRatingCommand(
                        itemId = parent.id,
                        userId = userId,
                        qualite = 3,
                        difficulte = 3,
                        flag = null,
                        correctif = CorrectifInput(duplicateText, Style.PERIPHRASE, null),
                        latencyMs = 1500,
                    ),
                )
            assertThat(r).isInstanceOf(SubmitRatingResult.Accepted::class)
            check(r is SubmitRatingResult.Accepted)
            assertThat(r.rating.proposedItemId).isEqualTo(existing.id)
            val autoGood = ratings.ratings.single { it.itemId == existing.id }
            assertThat(autoGood.qualite).isEqualTo(5)
            assertThat(items.findById(existing.id)).isNotNull()
            assertThat(proposed.links.single().itemId).isEqualTo(existing.id)
        }

    @Test
    fun `correctif with unchanged text and new pos patches the original item in place without a proposed item`() =
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
                        correctif = CorrectifInput(parent.definition, parent.style, Pos.POLYVALENT),
                        latencyMs = 1100,
                    ),
                )
            assertThat(r).isInstanceOf(SubmitRatingResult.Accepted::class)
            check(r is SubmitRatingResult.Accepted)
            assertThat(items.findById(parent.id)?.pos).isEqualTo(Pos.POLYVALENT)
            assertThat(items.items.values.none { it.source == Source.RATER_PROPOSED }).isEqualTo(true)
            assertThat(proposed.links.isEmpty()).isEqualTo(true)
            assertThat(r.rating.proposedItemId).isEqualTo(null)
            assertThat(ratings.ratings.size).isEqualTo(1)
        }

    @Test
    fun `correctif with changed text and pos creates a proposed item carrying the chosen pos`() =
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
                        correctif = CorrectifInput("Fruit defendu d'Eve", Style.PERIPHRASE, Pos.POLYVALENT),
                        latencyMs = 1500,
                    ),
                )
            assertThat(r).isInstanceOf(SubmitRatingResult.Accepted::class)
            val proposedItem = items.items.values.single { it.source == Source.RATER_PROPOSED }
            assertThat(proposedItem.pos).isEqualTo(Pos.POLYVALENT)
            assertThat(proposedItem.definition).isEqualTo("Fruit defendu d'Eve")
            assertThat(items.findById(parent.id)?.pos).isEqualTo(Pos.NOM_COMMUN)
        }

    @Test
    fun `correctif with changed text and no pos keeps the parent pos on the proposed item`() =
        runTest {
            val (uc, items, _, _, _) = newUseCase()
            val parent = seedItem(items)
            val userId = UserId(UUID.randomUUID())
            uc.execute(
                SubmitRatingCommand(
                    itemId = parent.id,
                    userId = userId,
                    qualite = 3,
                    difficulte = 3,
                    flag = null,
                    correctif = CorrectifInput("Fruit defendu d'Eve", Style.PERIPHRASE, null),
                    latencyMs = 1500,
                ),
            )
            val proposedItem = items.items.values.single { it.source == Source.RATER_PROPOSED }
            assertThat(proposedItem.pos).isEqualTo(parent.pos)
        }

    @Test
    fun `correctif by a cached maintainer is stamped gold`() =
        runTest {
            val rater = UserId(UUID.randomUUID())
            val roles = InMemoryMaintainerRoleRepository()
            roles.upsert(MaintainerRole(rater, "maintainer", Instant.parse("2026-05-30T00:00:00Z")))
            val postCutoffClock = Clock { Instant.parse("2026-05-30T09:00:00Z") }
            val (uc, items, _, _, _) = newUseCase(clock = postCutoffClock, roles = roles)
            val parent = seedItem(items)
            uc.execute(
                SubmitRatingCommand(
                    itemId = parent.id,
                    userId = rater,
                    qualite = 3,
                    difficulte = 3,
                    flag = null,
                    correctif = CorrectifInput("Fruit defendu d'Eve", Style.PERIPHRASE, null),
                    latencyMs = 1500,
                ),
            )
            val proposed = items.trainingWeights.entries.single()
            assertThat(proposed.value).isEqualTo(3.0)
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

    @Test
    fun `anon plus targetSenses is forbidden`() =
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
                        correctif = null,
                        latencyMs = 1000,
                        targetSenses = listOf("some gloss"),
                    ),
                )
            assertThat(r).isEqualTo(SubmitRatingResult.AnonTargetSensesForbidden)
        }

    @Test
    fun `auth rating with targetSenses merges glosses into sense inventory`() =
        runTest {
            val wordMeta = InMemoryWordMetaRepository()
            val (uc, items, _, _, _) = newUseCase(wordMeta = wordMeta)
            val parent = seedItem(items)
            val userId = UserId(UUID.randomUUID())
            val r =
                uc.execute(
                    SubmitRatingCommand(
                        itemId = parent.id,
                        userId = userId,
                        qualite = 4,
                        difficulte = 2,
                        flag = null,
                        correctif = null,
                        latencyMs = 1000,
                        targetSenses = listOf("fruit rouge", "fruit vert"),
                    ),
                )
            assertThat(r).isInstanceOf(SubmitRatingResult.Accepted::class)
            assertThat(wordMeta.rows[parent.mot]?.senseInventory)
                .isEqualTo(listOf("fruit rouge", "fruit vert"))
        }

    @Test
    fun `mergeIntoSenseInventory deduplicates by normalized form preserving first-seen spelling`() =
        runTest {
            val wordMeta = InMemoryWordMetaRepository()
            kotlinx.coroutines.runBlocking {
                wordMeta.save(
                    com.bliss.survey.domain.model.WordMeta(
                        mot = "POMME",
                        subTags = emptyList(),
                        senseInventory = listOf("Fruit du pommier"),
                        updatedAt = fixedNow,
                    ),
                )
            }
            val (uc, items, _, _, _) = newUseCase(wordMeta = wordMeta)
            val parent = seedItem(items)
            val userId = UserId(UUID.randomUUID())
            uc.execute(
                SubmitRatingCommand(
                    itemId = parent.id,
                    userId = userId,
                    qualite = 4,
                    difficulte = 2,
                    flag = null,
                    correctif = null,
                    latencyMs = 1000,
                    targetSenses = listOf("FRUIT DU POMMIER", "Pomme golden"),
                ),
            )
            // incoming first; "FRUIT DU POMMIER" and "Fruit du pommier" share a normalized key — incoming wins
            assertThat(wordMeta.rows["POMME"]?.senseInventory)
                .isEqualTo(listOf("FRUIT DU POMMIER", "Pomme golden"))
        }
}
