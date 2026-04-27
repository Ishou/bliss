// Mirror of grid/api Database.kt; consolidate when a third consumer appears.
package com.bliss.grid.worker.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.net.URI
import javax.sql.DataSource

object Database {
    private val log = LoggerFactory.getLogger(Database::class.java)
    private const val MAX_POOL_SIZE: Int = 4
    private const val POOL_NAME: String = "grid-worker-hikari"
    private const val DEFAULT_POSTGRES_PORT: Int = 5432

    @Volatile
    private var dataSource: HikariDataSource? = null

    fun dataSource(): DataSource? = dataSource

    /** Idempotent bootstrap. Worker has no no-DB mode — `DATABASE_URL` is required (ADR-0013 §6, §7). */
    @Synchronized
    fun start() {
        if (dataSource != null) return
        val raw = readDatabaseUrl() ?: error("DATABASE_URL is required for the grid-worker")
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
    fun stopForTesting() {
        dataSource?.close()
        dataSource = null
    }

    private fun readDatabaseUrl(): String? = System.getenv("DATABASE_URL") ?: System.getProperty("DATABASE_URL")

    internal fun toJdbcUrl(raw: String): String {
        if (raw.startsWith("jdbc:")) return raw
        val uri = parseUri(raw)
        val scheme = uri.scheme?.lowercase()
        require(scheme == "postgres" || scheme == "postgresql") {
            "DATABASE_URL must use postgres:// or postgresql:// scheme, got: $scheme"
        }
        val rawHost = requireNotNull(uri.host) { "DATABASE_URL is missing host" }
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

    internal fun extractCredentials(raw: String): Pair<String?, String?> {
        if (raw.startsWith("jdbc:")) return null to null
        val userInfo = parseUri(raw).userInfo ?: return null to null
        val parts = userInfo.split(":", limit = 2)
        return parts.getOrNull(0)?.takeIf { it.isNotEmpty() } to parts.getOrNull(1)
    }

    private fun parseUri(raw: String): URI =
        try {
            URI(raw)
        } catch (e: Exception) {
            throw IllegalStateException("DATABASE_URL is not a valid URI", e)
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
                maximumPoolSize = MAX_POOL_SIZE
                poolName = POOL_NAME
            }
        log.info("worker_database_pool_configured pool={} max_pool_size={}", POOL_NAME, MAX_POOL_SIZE)
        return HikariDataSource(config)
    }

    private fun runMigrations(ds: DataSource) {
        val result =
            Flyway
                .configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        log.info(
            "worker_database_migrations_applied target_schema_version={} migrations_executed={}",
            result.targetSchemaVersion ?: "none",
            result.migrationsExecuted,
        )
    }
}
