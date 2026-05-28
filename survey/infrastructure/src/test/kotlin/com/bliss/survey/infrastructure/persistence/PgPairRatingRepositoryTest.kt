package com.bliss.survey.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.ItemPair
import com.bliss.survey.domain.model.PairRating
import com.bliss.survey.domain.model.PairRatingId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.PreferenceVerdict
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgPairRatingRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var items: PgSurveyItemRepository
    private lateinit var pairRatings: PgPairRatingRepository

    private val now: Instant = Instant.parse("2026-05-25T12:00:00Z")

    @BeforeAll
    fun startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        pg = SurveyTestcontainer.startPostgres()
        dataSource = SurveyTestcontainer.dataSourceFor(pg)
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::pg.isInitialized) pg.stop()
    }

    @BeforeEach
    fun freshRepos() {
        if (!::dataSource.isInitialized) return
        items = PgSurveyItemRepository(dataSource)
        pairRatings = PgPairRatingRepository(dataSource)
    }

    @AfterEach
    fun truncate() {
        if (::dataSource.isInitialized) SurveyTestcontainer.truncateAll(dataSource)
    }

    @Test
    fun `insert auth pair rating round-trips and respects unique constraint in either order`() =
        runTest {
            val a = sampleItem(definition = "def a")
            val b = sampleItem(definition = "def b")
            items.insert(a)
            items.insert(b)
            val user = UserId(UUID.randomUUID())
            val first =
                PairRating(
                    id = PairRatingId(UUID.randomUUID()),
                    leftItemId = a.id,
                    rightItemId = b.id,
                    userId = user,
                    verdict = PreferenceVerdict.LEFT_WINS,
                    difficulte = 3,
                    latencyMs = 1500,
                    createdAt = now,
                )
            assertThat(pairRatings.insert(first)).isEqualTo(true)
            // Re-attempt in reversed order — the partial unique on LEAST/GREATEST blocks it.
            val flipped =
                first.copy(
                    id = PairRatingId(UUID.randomUUID()),
                    leftItemId = b.id,
                    rightItemId = a.id,
                    verdict = PreferenceVerdict.RIGHT_WINS,
                )
            assertThat(pairRatings.insert(flipped)).isEqualTo(false)
        }

    @Test
    fun `anon pair ratings are not deduplicated`() =
        runTest {
            val a = sampleItem(definition = "def a")
            val b = sampleItem(definition = "def b")
            items.insert(a)
            items.insert(b)
            val row =
                PairRating(
                    id = PairRatingId(UUID.randomUUID()),
                    leftItemId = a.id,
                    rightItemId = b.id,
                    userId = null,
                    verdict = PreferenceVerdict.LEFT_WINS,
                    difficulte = 2,
                    latencyMs = 800,
                    createdAt = now,
                )
            assertThat(pairRatings.insert(row)).isEqualTo(true)
            val second = row.copy(id = PairRatingId(UUID.randomUUID()))
            assertThat(pairRatings.insert(second)).isEqualTo(true)
        }

    @Test
    fun `pickPairForUser returns two items sharing the same mot`() =
        runTest {
            val a = sampleItem(definition = "def a")
            val b = sampleItem(definition = "def b")
            items.insert(a)
            items.insert(b)
            val pair = items.pickPairForUser(null, exclude = emptySet())
            assertThat(pair).isNotNull()
            check(pair is ItemPair)
            assertThat(pair.mot).isEqualTo("POMME")
            assertThat(pair.left.mot).isEqualTo(pair.right.mot)
        }

    @Test
    fun `pickPairForUser excludes mots with only one candidate`() =
        runTest {
            items.insert(sampleItem(definition = "alone"))
            assertThat(items.pickPairForUser(null, exclude = emptySet())).isEqualTo(null)
        }

    @Test
    fun `pickPairForUser respects authed caller's prior ratings`() =
        runTest {
            val a = sampleItem(definition = "def a")
            val b = sampleItem(definition = "def b")
            val c = sampleItem(definition = "def c")
            items.insert(a)
            items.insert(b)
            items.insert(c)
            val user = UserId(UUID.randomUUID())
            // user rates two of the three → only one unrated for this mot → no pair.
            val ratings = PgRatingRepository(dataSource)
            ratings.insert(authRating(a.id, user))
            ratings.insert(authRating(b.id, user))
            assertThat(items.pickPairForUser(user, exclude = emptySet())).isEqualTo(null)
        }

    @Test
    fun `pickPairForUser excludes items the caller already pair-rated`() =
        runTest {
            val a = sampleItem(definition = "def a")
            val b = sampleItem(definition = "def b")
            val c = sampleItem(definition = "def c")
            items.insert(a)
            items.insert(b)
            items.insert(c)
            val user = UserId(UUID.randomUUID())
            pairRatings.insert(
                PairRating(
                    id = PairRatingId(UUID.randomUUID()),
                    leftItemId = a.id,
                    rightItemId = b.id,
                    userId = user,
                    verdict = PreferenceVerdict.LEFT_WINS,
                    difficulte = 3,
                    latencyMs = 900,
                    createdAt = now,
                ),
            )
            // Only c is still unseen by this user; not enough for a pair.
            assertThat(items.pickPairForUser(user, exclude = emptySet())).isEqualTo(null)
        }

    private fun sampleItem(
        mot: String = "POMME",
        definition: String,
    ): SurveyItem =
        SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = mot,
            definition = definition,
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.ALIMENTS,
            style = Style.DEFINITION_DIRECTE,
            forceClaimed = 3,
            longueur = mot.length,
            source = Source.SYNTHETIC_V1,
            sourceBatch = "test",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = now,
        )

    private fun authRating(
        itemId: ItemId,
        userId: UserId,
    ): com.bliss.survey.domain.model.Rating =
        com.bliss.survey.domain.model.Rating(
            id =
                com.bliss.survey.domain.model
                    .RatingId(UUID.randomUUID()),
            itemId = itemId,
            userId = userId,
            submittedAs = com.bliss.survey.domain.model.SubmittedAs.AUTH,
            qualite = 3,
            difficulte = 3,
            flag = null,
            proposedItemId = null,
            latencyMs = null,
            createdAt = now,
        )
}
