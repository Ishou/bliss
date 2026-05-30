package com.bliss.survey.infrastructure.persistence

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import com.bliss.survey.domain.model.Categorie
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
import kotlinx.coroutines.runBlocking
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
class PgTransactionManagerTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var tx: PgTransactionManager
    private lateinit var items: PgSurveyItemRepository
    private lateinit var ratings: PgRatingRepository

    private val now: Instant = Instant.parse("2026-05-30T12:00:00Z")

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
        tx = PgTransactionManager(dataSource)
        items = PgSurveyItemRepository(dataSource)
        ratings = PgRatingRepository(dataSource)
    }

    @AfterEach
    fun truncate() {
        if (::dataSource.isInitialized) SurveyTestcontainer.truncateAll(dataSource)
    }

    @Test
    fun `commits the write when block succeeds`() =
        runBlocking {
            val item = sampleItem()
            items.insert(item)

            tx.inTransaction {
                ratings.insert(sampleRating(item.id))
            }

            assertThat(ratings.countByItem(item.id)).isEqualTo(1)
        }

    @Test
    fun `rolls back all writes when block throws`() =
        runBlocking {
            val item = sampleItem()
            items.insert(item)

            assertFailure {
                runBlocking {
                    tx.inTransaction {
                        ratings.insert(sampleRating(item.id))
                        error("boom")
                    }
                }
            }.messageContains("boom")

            assertThat(ratings.countByItem(item.id)).isEqualTo(0)
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

    private fun sampleRating(itemId: ItemId): Rating =
        Rating(
            id = RatingId(UUID.randomUUID()),
            itemId = itemId,
            userId = UserId(UUID.randomUUID()),
            submittedAs = SubmittedAs.AUTH,
            qualite = 3,
            difficulte = 3,
            flag = null,
            proposedItemId = null,
            latencyMs = 1000,
            createdAt = now,
        )
}
