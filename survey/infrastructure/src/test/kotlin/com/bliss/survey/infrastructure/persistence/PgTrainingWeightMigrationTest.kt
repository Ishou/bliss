package com.bliss.survey.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgTrainingWeightMigrationTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var items: PgSurveyItemRepository

    @BeforeAll
    fun startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        pg = SurveyTestcontainer.startPostgres()
        dataSource = SurveyTestcontainer.dataSourceFor(pg)
        items = PgSurveyItemRepository(dataSource)
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::pg.isInitialized) pg.stop()
    }

    @AfterEach
    fun truncate() {
        if (::dataSource.isInitialized) SurveyTestcontainer.truncateAll(dataSource)
    }

    @Test
    fun `inserted item defaults to training_weight 1_0`() =
        runTest {
            val id = ItemId(UUID.randomUUID())
            items.insert(
                SurveyItem(
                    id = id,
                    mot = "chat",
                    definition = "Animal domestique",
                    pos = Pos.NOM_COMMUN,
                    categorie = Categorie.FAUNE_FLORE,
                    style = Style.PERIPHRASE,
                    forceClaimed = 2,
                    longueur = 4,
                    source = Source.RATER_PROPOSED,
                    sourceBatch = "test",
                    tier = Tier.MID,
                    isCalibration = false,
                    expected = null,
                    retiredAt = null,
                    createdAt = Instant.parse("2026-05-30T00:00:00Z"),
                ),
            )
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT training_weight FROM survey_items WHERE item_id = ?").use { stmt ->
                    stmt.setObject(1, id.value)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        assertThat(rs.getBigDecimal(1).toDouble()).isEqualTo(1.0)
                    }
                }
            }
        }

    @Test
    fun `maintainer_roles accepts a maintainer row`() =
        runTest {
            val userId = UUID.randomUUID()
            dataSource.connection.use { conn ->
                conn
                    .prepareStatement(
                        "INSERT INTO maintainer_roles (user_id, role, changed_at) VALUES (?, 'maintainer', now())",
                    ).use { stmt ->
                        stmt.setObject(1, userId)
                        assertThat(stmt.executeUpdate()).isEqualTo(1)
                    }
            }
        }
}
