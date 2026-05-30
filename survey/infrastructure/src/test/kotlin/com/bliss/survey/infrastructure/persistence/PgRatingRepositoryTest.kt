package com.bliss.survey.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.survey.domain.model.CampaignId
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.FlagReason
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Rating
import com.bliss.survey.domain.model.RatingId
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SubmittedAs
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import com.zaxxer.hikari.HikariDataSource
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.runBlocking
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
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgRatingRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var ratings: PgRatingRepository
    private lateinit var items: PgSurveyItemRepository

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
        ratings = PgRatingRepository(dataSource)
        items = PgSurveyItemRepository(dataSource)
    }

    @AfterEach
    fun truncate() {
        if (::dataSource.isInitialized) SurveyTestcontainer.truncateAll(dataSource)
    }

    @Test
    fun `insert and findAuthRating round-trips`() =
        runTest {
            val item = sampleItem()
            items.insert(item)
            val userId = UserId(UUID.randomUUID())
            val rating = authRating(item.id, userId)
            ratings.insert(rating)
            val back = ratings.findAuthRating(item.id, userId)
            assertThat(back).isEqualTo(rating)
        }

    @Test
    fun `findAuthRating returns null for an anon rating`() =
        runTest {
            val item = sampleItem()
            items.insert(item)
            ratings.insert(anonRating(item.id))
            val back = ratings.findAuthRating(item.id, UserId(UUID.randomUUID()))
            assertThat(back).isNull()
        }

    @Test
    fun `countByItem includes both auth and anon ratings`() =
        runTest {
            val item = sampleItem()
            items.insert(item)
            ratings.insert(authRating(item.id, UserId(UUID.randomUUID())))
            ratings.insert(anonRating(item.id))
            assertThat(ratings.countByItem(item.id)).isEqualTo(2)
        }

    @Test
    fun `anonymise removes user_id and latency and truncates created_at to month`() =
        runTest {
            val item = sampleItem()
            items.insert(item)
            val userId = UserId(UUID.randomUUID())
            val original = authRating(item.id, userId).copy(latencyMs = 4200, createdAt = now)
            ratings.insert(original)
            ratings.anonymiseForUser(userId)
            // The find-by-auth lookup is intentionally null after anonymisation.
            assertThat(ratings.findAuthRating(item.id, userId)).isNull()
            // The row still counts toward the per-item total.
            assertThat(ratings.countByItem(item.id)).isEqualTo(1)
            // submitted_as remains 'auth' (spec §4.1 weighting); user_id is NULL; created_at is month-truncated.
            dataSource.connection.use { conn ->
                conn
                    .prepareStatement("SELECT submitted_as, user_id, latency_ms, created_at FROM ratings WHERE rating_id = ?")
                    .use { stmt ->
                        stmt.setObject(1, original.id.value)
                        stmt.executeQuery().use { rs ->
                            assertThat(rs.next()).isEqualTo(true)
                            assertThat(rs.getString("submitted_as")).isEqualTo("auth")
                            assertThat(rs.getObject("user_id")).isNull()
                            assertThat(rs.getObject("latency_ms")).isNull()
                            val truncated = rs.getTimestamp("created_at").toInstant()
                            val expected =
                                now
                                    .atOffset(ZoneOffset.UTC)
                                    .withDayOfMonth(1)
                                    .truncatedTo(ChronoUnit.DAYS)
                                    .toInstant()
                            assertThat(truncated).isEqualTo(expected)
                        }
                    }
            }
        }

    @Test
    fun `anonymise is idempotent over many invocations - property based`() =
        runBlocking {
            val item = sampleItem()
            items.insert(item)
            val userId = UserId(UUID.randomUUID())
            // Insert a single auth rating; running anonymise N times must converge.
            ratings.insert(authRating(item.id, userId).copy(latencyMs = 1234, createdAt = now))
            val runs = Arb.int(1..5).next()
            repeat(runs) { ratings.anonymiseForUser(userId) }
            dataSource.connection.use { conn ->
                conn
                    .prepareStatement("SELECT user_id, latency_ms, submitted_as FROM ratings WHERE item_id = ?")
                    .use { stmt ->
                        stmt.setObject(1, item.id.value)
                        stmt.executeQuery().use { rs ->
                            assertThat(rs.next()).isEqualTo(true)
                            assertThat(rs.getObject("user_id")).isNull()
                            assertThat(rs.getObject("latency_ms")).isNull()
                            assertThat(rs.getString("submitted_as")).isEqualTo("auth")
                        }
                    }
            }
        }

    @Test
    fun `aggregateForExport splits sums and counts between auth and anon`() =
        runTest {
            val item = sampleItem()
            items.insert(item)
            // Ratings only settle once their campaign closed past the 8s grace.
            val campaign = CampaignId(insertClosedCampaign("round-7", closedAt = now.minusSeconds(60)))
            ratings.insert(authRating(item.id, UserId(UUID.randomUUID())).copy(qualite = 4, difficulte = 2, campaignId = campaign))
            ratings.insert(authRating(item.id, UserId(UUID.randomUUID())).copy(qualite = 5, difficulte = 3, campaignId = campaign))
            ratings.insert(anonRating(item.id).copy(qualite = 3, difficulte = 4, campaignId = campaign))
            val flagged = anonRating(item.id).copy(flag = FlagReason.AUTRE, campaignId = campaign)
            ratings.insert(flagged)
            val out = ratings.aggregateForExport(since = null, settledBefore = now.minusSeconds(8))
            assertThat(out).isNotNull()
            assertThat(out.size).isEqualTo(1)
            val agg = out[0]
            assertThat(agg.itemId).isEqualTo(item.id)
            assertThat(agg.qualiteAuthSum).isEqualTo(9)
            assertThat(agg.qualiteAuthN).isEqualTo(2)
            assertThat(agg.qualiteAnonSum).isEqualTo(3 + flagged.qualite)
            assertThat(agg.qualiteAnonN).isEqualTo(2)
            assertThat(agg.flagCount).isEqualTo(1)
            assertThat(agg.qualiteSquaredAuthSum).isEqualTo(16 + 25)
        }

    @Test
    fun `aggregateForExport excludes open and within-grace campaigns`() =
        runTest {
            val settledItem = sampleItem()
            // survey_items_content_uniq is on (mot, definition), so each item needs distinct content.
            val openItem = sampleItem().copy(id = ItemId(UUID.randomUUID()), mot = "PAIN", definition = "Aliment de boulangerie")
            val graceItem = sampleItem().copy(id = ItemId(UUID.randomUUID()), mot = "CHAT", definition = "Felin domestique")
            items.insert(settledItem)
            items.insert(openItem)
            items.insert(graceItem)

            val settled = CampaignId(insertClosedCampaign("settled", closedAt = now.minusSeconds(60)))
            val open = CampaignId(insertCampaignRow("open"))
            val grace = CampaignId(insertClosedCampaign("grace", closedAt = now.minusSeconds(3)))

            ratings.insert(anonRating(settledItem.id).copy(campaignId = settled))
            ratings.insert(anonRating(openItem.id).copy(campaignId = open))
            ratings.insert(anonRating(graceItem.id).copy(campaignId = grace))

            val out = ratings.aggregateForExport(since = null, settledBefore = now.minusSeconds(8))
            assertThat(out.size).isEqualTo(1)
            assertThat(out[0].itemId).isEqualTo(settledItem.id)
        }

    @Test
    fun `deleteByIds removes only the named ratings and is a no-op on empty`() =
        runTest {
            val item = sampleItem()
            items.insert(item)
            val keep = authRating(item.id, UserId(UUID.randomUUID()))
            val drop = authRating(item.id, UserId(UUID.randomUUID()))
            ratings.insert(keep)
            ratings.insert(drop)

            ratings.deleteByIds(emptyList())
            assertThat(ratings.countByItem(item.id)).isEqualTo(2)

            ratings.deleteByIds(listOf(drop.id))
            assertThat(ratings.countByItem(item.id)).isEqualTo(1)
            assertThat(ratings.findAuthRating(item.id, keep.userId!!)).isNotNull()
        }

    @Test
    fun `findAuthRating returns null when no rating exists`() =
        runTest {
            assertThat(ratings.findAuthRating(ItemId(UUID.randomUUID()), UserId(UUID.randomUUID()))).isNull()
        }

    @Test
    fun `insert and read back round-trips campaign_id`() =
        runTest {
            val item = sampleItem()
            items.insert(item)
            val campaignUuid = insertCampaignRow("round-7")
            val userId = UserId(UUID.randomUUID())
            val rating = authRating(item.id, userId).copy(campaignId = CampaignId(campaignUuid))
            ratings.insert(rating)

            val back = ratings.findAuthRating(item.id, userId)
            assertThat(back).isNotNull()
            assertThat(back!!.campaignId).isEqualTo(CampaignId(campaignUuid))

            dataSource.connection.use { c ->
                c.prepareStatement("SELECT campaign_id FROM ratings WHERE rating_id = ?").use { s ->
                    s.setObject(1, rating.id.value)
                    s.executeQuery().use { rs ->
                        assertThat(rs.next()).isEqualTo(true)
                        assertThat(rs.getObject("campaign_id", UUID::class.java)).isEqualTo(campaignUuid)
                    }
                }
            }
        }

    private fun insertCampaignRow(label: String): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    "INSERT INTO campaigns (campaign_id, batch_label) VALUES (?, ?)",
                ).use { s ->
                    s.setObject(1, id)
                    s.setString(2, label)
                    s.executeUpdate()
                }
        }
        return id
    }

    private fun insertClosedCampaign(
        label: String,
        closedAt: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    "INSERT INTO campaigns (campaign_id, batch_label, closed_at) VALUES (?, ?, ?)",
                ).use { s ->
                    s.setObject(1, id)
                    s.setString(2, label)
                    s.setTimestamp(3, java.sql.Timestamp.from(closedAt))
                    s.executeUpdate()
                }
        }
        return id
    }

    private fun sampleItem(): SurveyItem =
        SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = "POULE",
            definition = "Femelle du coq",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.ANIMALS,
            style = Style.PERIPHRASE,
            forceClaimed = 2,
            longueur = 5,
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
    ): Rating =
        Rating(
            id = RatingId(UUID.randomUUID()),
            itemId = itemId,
            userId = userId,
            submittedAs = SubmittedAs.AUTH,
            qualite = 3,
            difficulte = 3,
            flag = null,
            proposedItemId = null,
            latencyMs = 1000,
            createdAt = now,
        )

    private fun anonRating(itemId: ItemId): Rating =
        Rating(
            id = RatingId(UUID.randomUUID()),
            itemId = itemId,
            userId = null,
            submittedAs = SubmittedAs.ANON,
            qualite = 3,
            difficulte = 3,
            flag = null,
            proposedItemId = null,
            latencyMs = null,
            createdAt = now,
        )
}
