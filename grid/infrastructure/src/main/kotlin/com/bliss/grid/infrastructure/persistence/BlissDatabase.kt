package com.bliss.grid.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.net.URI
import javax.sql.DataSource

/**
 * Shared Postgres bootstrap for `grid-api` and `grid-worker` (ADR-0013 §6, ADR-0009).
 *
 * Env contract: `DATABASE_URL` carrying the Postgres URI from CNPG's `<cluster>-app`
 * secret, in the form `postgres://<user>:<password>@<host>:<port>/<db>` (alias
 * `postgresql://` accepted). Already-`jdbc:` URLs pass through untouched (the
 * Testcontainers happy path).
 *
 * Each consumer instantiates its own [BlissDatabase] with a distinct pool name and
 * size. Pass `requireUrl = true` to fail loudly when `DATABASE_URL` is unset (the
 * worker policy); pass `false` to log-and-skip (the API policy, which lets dev/CI
 * boot without a database).
 */
class BlissDatabase(
    private val poolName: String,
    private val maxPoolSize: Int,
    private val requireUrl: Boolean,
) {
    private val log = LoggerFactory.getLogger("$LOGGER_BASE.$poolName")

    @Volatile
    private var dataSource: HikariDataSource? = null

    /** Active [DataSource], or `null` when [start] ran without `DATABASE_URL`. */
    fun dataSource(): DataSource? = dataSource

    /** Idempotent. Reads `DATABASE_URL` (env or JVM property), pools it, runs Flyway. */
    @Synchronized
    fun start() {
        if (dataSource != null) {
            log.debug("database_already_started pool={}", poolName)
            return
        }
        val raw = readDatabaseUrl()
        if (raw.isNullOrBlank()) {
            if (requireUrl) error("DATABASE_URL is required for $poolName")
            log.warn(
                "database_url_unset action=skip_migration pool={} " +
                    "reason=no_DATABASE_URL_env hint=local_dev_or_ci_without_postgres",
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
    fun stopForTesting() {
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
                poolName = this@BlissDatabase.poolName
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
        private const val LOGGER_BASE = "com.bliss.grid.infrastructure.persistence.BlissDatabase"
        private const val DEFAULT_POSTGRES_PORT: Int = 5432

        internal fun readDatabaseUrl(): String? = System.getenv("DATABASE_URL") ?: System.getProperty("DATABASE_URL")

        /**
         * Converts `postgres://user:pw@host:port/db?...` (CNPG-style) into the
         * JDBC URL Hikari expects: `jdbc:postgresql://host:port/db?...`. Strips
         * userinfo so passwords never appear in URL-echoing logs. `jdbc:` inputs
         * pass through (Testcontainers happy path).
         */
        fun toJdbcUrl(raw: String): String {
            if (raw.startsWith("jdbc:")) return raw
            val uri = parseUri(raw)
            val scheme = uri.scheme?.lowercase()
            require(scheme == "postgres" || scheme == "postgresql") {
                "DATABASE_URL must use postgres:// or postgresql:// scheme, got: $scheme"
            }
            val rawHost = requireNotNull(uri.host) { "DATABASE_URL is missing host" }
            // java.net.URI preserves brackets in getHost() on Java 9+ (e.g. "[::1]").
            // Add brackets only when the host is an unbracketed IPv6 literal.
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
                throw IllegalStateException("DATABASE_URL is not a valid URI", e)
            }
    }
}
