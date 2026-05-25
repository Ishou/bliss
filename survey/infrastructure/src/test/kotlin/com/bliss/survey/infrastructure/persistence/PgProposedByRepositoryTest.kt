package com.bliss.survey.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
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
class PgProposedByRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var items: PgSurveyItemRepository
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
        proposedBy = PgProposedByRepository(dataSource)
    }

    @AfterEach
    fun truncate() {
        if (::dataSource.isInitialized) SurveyTestcontainer.truncateAll(dataSource)
    }

    @Test
    fun `listOptedOutByUser returns only opted-out contributions`() =
        runTest {
            val author = UserId(UUID.randomUUID())
            val a = sampleItem("A")
            val b = sampleItem("B")
            items.insert(a)
            items.insert(b)
            proposedBy.insert(a.id, author, optedOut = false)
            proposedBy.insert(b.id, author, optedOut = true)
            assertThat(proposedBy.listOptedOutByUser(author)).containsExactlyInAnyOrder(b.id)
        }

    @Test
    fun `setOptOut flips every contribution for the user`() =
        runTest {
            val author = UserId(UUID.randomUUID())
            val a = sampleItem("A")
            val b = sampleItem("B")
            items.insert(a)
            items.insert(b)
            proposedBy.insert(a.id, author, optedOut = false)
            proposedBy.insert(b.id, author, optedOut = false)
            proposedBy.setOptOut(author, optedOut = true)
            assertThat(proposedBy.listOptedOutByUser(author)).containsExactlyInAnyOrder(a.id, b.id)
        }

    @Test
    fun `deleteByUser removes every proposed_by row`() =
        runTest {
            val author = UserId(UUID.randomUUID())
            val a = sampleItem("A")
            items.insert(a)
            proposedBy.insert(a.id, author, optedOut = false)
            proposedBy.deleteByUser(author)
            assertThat(proposedBy.listOptedOutByUser(author)).isEmpty()
        }

    @Test
    fun `listOptedOutByUser leaves other users untouched`() =
        runTest {
            val mine = UserId(UUID.randomUUID())
            val theirs = UserId(UUID.randomUUID())
            val a = sampleItem("A")
            val b = sampleItem("B")
            items.insert(a)
            items.insert(b)
            proposedBy.insert(a.id, mine, optedOut = true)
            proposedBy.insert(b.id, theirs, optedOut = true)
            proposedBy.deleteByUser(mine)
            assertThat(proposedBy.listOptedOutByUser(mine)).isEmpty()
            assertThat(proposedBy.listOptedOutByUser(theirs)).containsExactlyInAnyOrder(b.id)
        }

    private fun sampleItem(mot: String): SurveyItem =
        SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = mot,
            definition = "Definition for $mot",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.ANIMALS,
            style = Style.PERIPHRASE,
            forceClaimed = 2,
            longueur = mot.length,
            source = Source.RATER_PROPOSED,
            sourceBatch = "test",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = now,
        )
}
