package com.bliss.survey.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.net.URI
import javax.sql.DataSource

/** Hikari + Flyway bootstrap for the survey context (ADR-0056). */
object SurveyDatabase {
    private const val FLYWAY_HISTORY_TABLE = "flyway_schema_history_survey"
    private const val DEFAULT_POSTGRES_PORT: Int = 5432

    fun create(
        jdbcUrl: String,
        user: String,
        password: String,
        maxPoolSize: Int = 10,
    ): DataSource {
        val config =
            HikariConfig().apply {
                this.jdbcUrl = toJdbcUrl(jdbcUrl)
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

    // Converts libpq-style postgresql:// URIs to Hikari's expected jdbc:postgresql:// form.
    fun toJdbcUrl(raw: String): String {
        if (raw.startsWith("jdbc:")) return raw
        val uri =
            try {
                URI(raw)
            } catch (e: Exception) {
                throw IllegalStateException("SURVEY_JDBC_URL is not a valid URI", e)
            }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "postgres" || scheme == "postgresql") {
            "SURVEY_JDBC_URL must use postgres:// or postgresql:// scheme, got: $scheme"
        }
        val rawHost = requireNotNull(uri.host) { "SURVEY_JDBC_URL is missing host" }
        val host =
            when {
                rawHost.startsWith("[") -> rawHost
                rawHost.contains(':') -> "[$rawHost]"
                else -> rawHost
            }
        val port = if (uri.port == -1) DEFAULT_POSTGRES_PORT else uri.port
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "jdbc:postgresql://$host:$port$path$query"
    }
}
