package com.bliss.survey.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/** Hikari + Flyway bootstrap for the survey context (ADR-0056). */
object SurveyDatabase {
    private const val FLYWAY_HISTORY_TABLE = "flyway_schema_history_survey"

    fun create(
        jdbcUrl: String,
        user: String,
        password: String,
        maxPoolSize: Int = 10,
    ): DataSource {
        val config =
            HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                this.username = user
                this.password = password
                this.maximumPoolSize = maxPoolSize
                this.poolName = "survey"
            }
        val ds = HikariDataSource(config)
        runMigrations(ds)
        return ds
    }

    fun runMigrations(ds: DataSource) {
        Flyway
            .configure()
            .dataSource(ds)
            .table(FLYWAY_HISTORY_TABLE)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
