package com.bliss.survey.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.containsOnly
import assertk.assertions.hasSize
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
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.routing.KCoveragePolicy
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
import java.time.temporal.ChronoUnit
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgSurveyItemRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var items: PgSurveyItemRepository
    private lateinit var ratings: PgRatingRepository
    private lateinit var proposedBy: PgProposedByRepository

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
        ratings = PgRatingRepository(dataSource)
        proposedBy = PgProposedByRepository(dataSource)
    }

    @AfterEach
    fun truncate() {
        if (::dataSource.isInitialized) SurveyTestcontainer.truncateAll(dataSource)
    }

    @Test
    fun `insert and findById round-trips`() =
        runTest {
            val item = sampleItem()
            items.insert(item)
            assertThat(items.findById(item.id)).isEqualTo(item)
        }

    @Test
    fun `findById returns null for unknown id`() =
        runTest {
            assertThat(items.findById(ItemId(UUID.randomUUID()))).isNull()
        }

    @Test
    fun `retire sets retiredAt and is filtered from countUnretiredByTier`() =
        runTest {
            val item = sampleItem(tier = Tier.HIGH)
            items.insert(item)
            items.retire(item.id, now)
            assertThat(items.findById(item.id)?.retiredAt).isNotNull()
            val counts = items.countUnretiredByTier()
            assertThat(counts[Tier.HIGH]).isEqualTo(0)
        }

    @Test
    fun `duplicate mot+definition while unretired is rejected by V5 invariant`() =
        runTest {
            val first = sampleItem(mot = "PAIN")
            items.insert(first)
            val duplicate = first.copy(id = ItemId(UUID.randomUUID()))
            val thrown =
                runCatching { items.insert(duplicate) }.exceptionOrNull()
            check(thrown is org.postgresql.util.PSQLException) {
                "expected PSQLException on duplicate (mot, definition); got $thrown"
            }
            check(thrown.sqlState == "23505") {
                "expected unique-violation sqlstate 23505; got ${thrown.sqlState}"
            }
        }

    @Test
    fun `insertIfAbsent inserts a new row and returns it`() =
        runTest {
            val item = sampleItem(mot = "PAIN")
            val returned = items.insertIfAbsent(item)
            assertThat(returned.id).isEqualTo(item.id)
            assertThat(items.findById(item.id)).isNotNull()
        }

    @Test
    fun `insertIfAbsent on duplicate mot+definition returns the existing row without inserting`() =
        runTest {
            val first = sampleItem(mot = "PAIN")
            items.insert(first)
            val duplicate = first.copy(id = ItemId(UUID.randomUUID()))
            val returned = items.insertIfAbsent(duplicate)
            assertThat(returned.id).isEqualTo(first.id)
            assertThat(items.findById(duplicate.id)).isNull()
        }

    @Test
    fun `retiring an item frees its mot+definition for re-insertion`() =
        runTest {
            val original = sampleItem(mot = "PAIN")
            items.insert(original)
            items.retire(original.id, now)
            val fresh = original.copy(id = ItemId(UUID.randomUUID()))
            items.insert(fresh)
            assertThat(items.findById(fresh.id)).isNotNull()
        }

    @Test
    fun `pickUnratedForUser returns null when no items match`() =
        runTest {
            val pick = items.pickUnratedForUser(null, Tier.MID, exclude = emptySet())
            assertThat(pick).isNull()
        }

    @Test
    fun `pickUnratedForUser returns a K=0 item before K=1`() =
        runTest {
            val unrated = sampleItem(mot = "POULE")
            val onceRated = sampleItem(mot = "VACHE")
            items.insert(unrated)
            items.insert(onceRated)
            // give onceRated a single rating
            ratings.insert(authRating(onceRated.id, UserId(UUID.randomUUID())))
            val picked = items.pickUnratedForUser(null, Tier.MID, exclude = emptySet())
            assertThat(picked?.id).isEqualTo(unrated.id)
        }

    @Test
    fun `pickUnratedForUser excludes items in exclude set`() =
        runTest {
            val a = sampleItem(mot = "A")
            val b = sampleItem(mot = "B")
            items.insert(a)
            items.insert(b)
            val picked = items.pickUnratedForUser(null, Tier.MID, exclude = setOf(a.id))
            assertThat(picked?.id).isEqualTo(b.id)
        }

    @Test
    fun `pickUnratedForUser skips items the user has already rated`() =
        runTest {
            val userId = UserId(UUID.randomUUID())
            val rated = sampleItem(mot = "RATED")
            val fresh = sampleItem(mot = "FRESH")
            items.insert(rated)
            items.insert(fresh)
            ratings.insert(authRating(rated.id, userId))
            val picked = items.pickUnratedForUser(userId, Tier.MID, exclude = emptySet())
            assertThat(picked?.id).isEqualTo(fresh.id)
        }

    @Test
    fun `pickUnratedForUser excludes items by content match to user's rated history`() =
        runTest {
            val userId = UserId(UUID.randomUUID())
            // Realistic shape: rated item is retired, then a fresh item with identical content is inserted.
            val rated = sampleItem(mot = "PAIN")
            items.insert(rated)
            ratings.insert(authRating(rated.id, userId))
            items.retire(rated.id, now)
            val reincarnation = rated.copy(id = ItemId(UUID.randomUUID()), retiredAt = null)
            items.insert(reincarnation)
            val picked = items.pickUnratedForUser(userId, Tier.MID, exclude = emptySet())
            assertThat(picked).isNull()
        }

    @Test
    fun `pickUnratedForUser anon caller is unchanged by the content dedup`() =
        runTest {
            val someoneElse = UserId(UUID.randomUUID())
            val rated = sampleItem(mot = "PAIN")
            items.insert(rated)
            ratings.insert(authRating(rated.id, someoneElse))
            items.retire(rated.id, now)
            val reincarnation = rated.copy(id = ItemId(UUID.randomUUID()), retiredAt = null)
            items.insert(reincarnation)
            // Anon caller: K=0 path matches reincarnation (no rating on its item_id) regardless of content history.
            val picked = items.pickUnratedForUser(null, Tier.MID, exclude = emptySet())
            assertThat(picked?.id).isEqualTo(reincarnation.id)
        }

    @Test
    fun `listSaturated returns items meeting tier-specific K coverage`() =
        runTest {
            val item = sampleItem(tier = Tier.MID)
            items.insert(item)
            // give it 3 ratings
            repeat(3) { ratings.insert(authRating(item.id, UserId(UUID.randomUUID()))) }
            val policy = KCoveragePolicy.DEFAULT
            assertThat(items.listSaturated(policy)).containsOnly(item.id)
        }

    @Test
    fun `listProposedByUser joins survey_items and proposed_by`() =
        runTest {
            val author = UserId(UUID.randomUUID())
            val proposed = sampleItem(source = Source.RATER_PROPOSED, mot = "FROMAGE")
            items.insert(proposed)
            proposedBy.insert(proposed.id, author, optedOut = false)
            val contribs = items.listProposedByUser(author)
            assertThat(contribs).hasSize(1)
            assertThat(contribs[0].item.id).isEqualTo(proposed.id)
            assertThat(contribs[0].optedOut).isEqualTo(false)
            assertThat(contribs[0].kCoverage).isEqualTo(0)
        }

    @Test
    fun `deleteByIds removes the listed items`() =
        runTest {
            val a = sampleItem(mot = "A")
            val b = sampleItem(mot = "B")
            items.insert(a)
            items.insert(b)
            items.deleteByIds(listOf(a.id))
            assertThat(items.findById(a.id)).isNull()
            assertThat(items.findById(b.id)?.id).isEqualTo(b.id)
        }

    @Test
    fun `deleteByIds on empty collection is a no-op`() =
        runTest {
            val item = sampleItem()
            items.insert(item)
            items.deleteByIds(emptyList())
            assertThat(items.findById(item.id)?.id).isEqualTo(item.id)
        }

    @Test
    fun `countUnretiredByTier reports zero for empty tiers`() =
        runTest {
            items.insert(sampleItem(tier = Tier.HIGH))
            val counts = items.countUnretiredByTier()
            assertThat(counts.keys).containsExactlyInAnyOrder(Tier.HIGH, Tier.MID, Tier.LOW, Tier.EXCLUDED)
            assertThat(counts[Tier.HIGH]).isEqualTo(1)
            assertThat(counts[Tier.LOW]).isEqualTo(0)
        }

    private fun sampleItem(
        mot: String = "POULE",
        tier: Tier = Tier.MID,
        source: Source = Source.SYNTHETIC_V1,
    ): SurveyItem =
        SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = mot,
            definition = "Definition for $mot",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.ANIMALS,
            style = Style.PERIPHRASE,
            forceClaimed = 2,
            longueur = mot.length,
            source = source,
            sourceBatch = "test-batch",
            tier = tier,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = now.truncatedTo(ChronoUnit.MILLIS),
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
