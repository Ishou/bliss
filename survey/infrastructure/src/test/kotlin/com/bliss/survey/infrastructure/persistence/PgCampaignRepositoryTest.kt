package com.bliss.survey.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.survey.domain.model.CampaignId
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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgCampaignRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var campaigns: PgCampaignRepository

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
    fun freshRepo() {
        if (!::dataSource.isInitialized) return
        campaigns = PgCampaignRepository(dataSource)
    }

    @AfterEach
    fun truncate() {
        if (::dataSource.isInitialized) SurveyTestcontainer.truncateAll(dataSource)
    }

    private fun insertCampaign(
        label: String,
        opened: Instant? = null,
        closed: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    "INSERT INTO campaigns (campaign_id, batch_label, opened_at, closed_at) VALUES (?, ?, COALESCE(?, now()), ?)",
                ).use { s ->
                    s.setObject(1, id)
                    s.setString(2, label)
                    if (opened != null) s.setTimestamp(3, Timestamp.from(opened)) else s.setNull(3, java.sql.Types.TIMESTAMP)
                    if (closed != null) s.setTimestamp(4, Timestamp.from(closed)) else s.setNull(4, java.sql.Types.TIMESTAMP)
                    s.executeUpdate()
                }
        }
        return id
    }

    @Test
    fun `findOpen returns null on empty table`() =
        runTest {
            assertThat(campaigns.findOpen()).isNull()
        }

    @Test
    fun `findOpen returns the open row`() =
        runTest {
            val id = insertCampaign("round-7")
            val found = campaigns.findOpen()
            assertThat(found).isNotNull()
            assertThat(found!!.id).isEqualTo(CampaignId(id))
            assertThat(found.isOpen).isEqualTo(true)
        }

    @Test
    fun `findOpen returns null when only closed campaigns exist`() =
        runTest {
            insertCampaign("round-6", closed = Instant.parse("2026-05-29T12:00:00Z"))
            assertThat(campaigns.findOpen()).isNull()
        }

    @Test
    fun `partial unique index forbids two open campaigns`() =
        runTest {
            insertCampaign("round-7")
            var threw = false
            try {
                insertCampaign("round-8")
            } catch (e: Exception) {
                threw = true
            }
            assertThat(threw).isEqualTo(true)
        }

    @Test
    fun `findCurrent falls back to most recently opened closed campaign`() =
        runTest {
            insertCampaign(
                "round-5",
                opened = Instant.parse("2026-05-28T10:00:00Z"),
                closed = Instant.parse("2026-05-28T12:00:00Z"),
            )
            insertCampaign(
                "round-6",
                opened = Instant.parse("2026-05-29T10:00:00Z"),
                closed = Instant.parse("2026-05-29T12:00:00Z"),
            )
            val found = campaigns.findCurrent()
            assertThat(found).isNotNull()
            assertThat(found!!.batchLabel).isEqualTo("round-6")
        }

    @Test
    fun `findById returns the persisted campaign`() =
        runTest {
            val id = insertCampaign("round-7")
            val found = campaigns.findById(CampaignId(id))
            assertThat(found).isNotNull()
            assertThat(found!!.id).isEqualTo(CampaignId(id))
            assertThat(found.batchLabel).isEqualTo("round-7")
        }

    @Test
    fun `findById returns null for an unknown id`() =
        runTest {
            assertThat(campaigns.findById(CampaignId(UUID.randomUUID()))).isNull()
        }
}
