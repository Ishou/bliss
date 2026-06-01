package com.bliss.survey.worker

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.infrastructure.persistence.SurveyDatabase
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import javax.sql.DataSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestBatchTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource

    @BeforeAll
    fun startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        pg = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply { start() }
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = pg.jdbcUrl
                    username = pg.username
                    password = pg.password
                    maximumPoolSize = 4
                    poolName = "survey-worker-test"
                },
            )
        SurveyDatabase.runMigrations(dataSource)
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::pg.isInitialized) pg.stop()
    }

    @Test
    fun `ingest accepts well-formed rows and rejects ones that fail filters or parse`() {
        truncate(dataSource)
        val csv = Files.createTempFile("ingest", ".csv")
        Files.writeString(
            csv,
            buildString {
                append("mot;definition;pos;categorie;style;force;longueur;source\n")
                append("PAIN;Aliment de boulangerie;nom_commun;nourriture;definition_directe;1;4;gold\n")
                append("POULE;Femelle du coq;nom_commun;faune_flore;periphrase;2;5;gold\n")
                append("TRAIN;Transport ferroviaire;nom_commun;objet;definition_directe;1;5;gold\n")
                // Reject: Filter4 stereotype prefix "personne qui "
                append("HOMME;personne qui marche;nom_commun;autre;definition_directe;2;5;gold\n")
            },
        )

        val report = runIngest(dataSource, csv, sourceBatch = "test_v1", tier = Tier.MID)

        assertThat(report.accepted).isEqualTo(3)
        assertThat(report.rejected.size).isEqualTo(1)
        assertThat(countSurveyItems(dataSource)).isEqualTo(3)
    }

    private fun truncate(ds: DataSource) {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    "TRUNCATE survey_items, ratings, proposed_by, user_progress RESTART IDENTITY CASCADE",
                ).use { it.executeUpdate() }
        }
    }

    private fun countSurveyItems(ds: DataSource): Int =
        ds.connection.use { conn ->
            conn.prepareStatement("SELECT count(*) FROM survey_items").use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
}
