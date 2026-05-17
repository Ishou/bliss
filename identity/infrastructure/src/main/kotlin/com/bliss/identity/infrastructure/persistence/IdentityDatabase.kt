package com.bliss.identity.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.net.URI
import javax.sql.DataSource

// Postgres bootstrap for the identity context (ADR-0044).
class IdentityDatabase(
    private val poolName: String,
    private val maxPoolSize: Int,
    private val requireUrl: Boolean,
) {
    private val log = LoggerFactory.getLogger("$LOGGER_BASE.$poolName")

    @Volatile
    private var dataSource: HikariDataSource? = null

    /** Active [DataSource], or `null` when [start] ran without `IDENTITY_DATABASE_URL`. */
    fun dataSource(): DataSource? = dataSource

    /** Idempotent. Reads `IDENTITY_DATABASE_URL` (env or JVM property), pools it, runs Flyway. */
    @Synchronized
    fun start() {
        if (dataSource != null) {
            log.debug("database_already_started pool={}", poolName)
            return
        }
        val raw = readDatabaseUrl()
        if (raw.isNullOrBlank()) {
            if (requireUrl) error("IDENTITY_DATABASE_URL is required for $poolName")
            log.warn(
                "database_url_unset action=skip_migration pool={} " +
                    "reason=no_IDENTITY_DATABASE_URL_env hint=local_dev_or_ci_without_postgres",
                poolName,
            )
            return
        }
        val jdbcUrl = toJdbcUrl(raw)
        val (user, password) = extractCredentials(raw)
        val ds = buildDataSource(jdbcUrl, user, password)
        try {
            runMigrations(ds)
        } catch (e: Exception) {
            ds.close()
            throw e
        }
        dataSource = ds
    }

    @Synchronized
    fun stop() {
        dataSource?.close()
        dataSource = null
    }

    private fun buildDataSource(
        jdbcUrl: String,
        user: String?,
        password: String?,
    ): HikariDataSource {
        val config =
            HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                if (user != null) username = user
                if (password != null) this.password = password
                maximumPoolSize = maxPoolSize
                poolName = this@IdentityDatabase.poolName
            }
        log.info("database_pool_configured pool={} max_pool_size={}", poolName, maxPoolSize)
        return HikariDataSource(config)
    }

    private fun runMigrations(ds: DataSource) {
        val result =
            Flyway
                .configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .table("flyway_schema_history_identity")
                .load()
                .migrate()
        log.info(
            "database_migrations_applied pool={} initial_schema_version={} target_schema_version={} migrations_executed={}",
            poolName,
            result.initialSchemaVersion ?: "none",
            result.targetSchemaVersion ?: "none",
            result.migrationsExecuted,
        )
    }

    companion object {
        private const val LOGGER_BASE = "com.bliss.identity.infrastructure.persistence.IdentityDatabase"
        private const val DEFAULT_POSTGRES_PORT: Int = 5432

        internal fun readDatabaseUrl(): String? = System.getenv("IDENTITY_DATABASE_URL") ?: System.getProperty("IDENTITY_DATABASE_URL")

        // Strips userinfo from postgres:// URLs; jdbc: inputs pass through unchanged.
        fun toJdbcUrl(raw: String): String {
            if (raw.startsWith("jdbc:")) return raw
            val uri = parseUri(raw)
            val scheme = uri.scheme?.lowercase()
            require(scheme == "postgres" || scheme == "postgresql") {
                "IDENTITY_DATABASE_URL must use postgres:// or postgresql:// scheme, got: $scheme"
            }
            val rawHost = requireNotNull(uri.host) { "IDENTITY_DATABASE_URL is missing host" }
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

        /** Pulls `user:password` out of the URI's userinfo for Hikari's auth fields. */
        fun extractCredentials(raw: String): Pair<String?, String?> {
            if (raw.startsWith("jdbc:")) return null to null
            val userInfo = parseUri(raw).userInfo ?: return null to null
            val parts = userInfo.split(":", limit = 2)
            return parts.getOrNull(0)?.takeIf { it.isNotEmpty() } to parts.getOrNull(1)
        }

        private fun parseUri(raw: String): URI =
            try {
                URI(raw)
            } catch (e: Exception) {
                throw IllegalStateException("IDENTITY_DATABASE_URL is not a valid URI", e)
            }
    }
}
