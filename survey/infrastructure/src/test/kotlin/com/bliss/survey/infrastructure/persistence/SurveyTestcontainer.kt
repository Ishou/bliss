package com.bliss.survey.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

/** Shared testcontainers + Hikari + Flyway bootstrap for survey-infrastructure tests. */
internal object SurveyTestcontainer {
    fun startPostgres(): PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply { start() }

    fun dataSourceFor(pg: PostgreSQLContainer<*>): HikariDataSource {
        val ds =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = pg.jdbcUrl
                    username = pg.username
                    password = pg.password
                    maximumPoolSize = 4
                    poolName = "survey-test"
                },
            )
        SurveyDatabase.runMigrations(ds)
        return ds
    }

    fun truncateAll(ds: DataSource) {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    "TRUNCATE survey_items, ratings, proposed_by, user_progress, pair_ratings RESTART IDENTITY CASCADE",
                ).use { it.executeUpdate() }
        }
    }
}
