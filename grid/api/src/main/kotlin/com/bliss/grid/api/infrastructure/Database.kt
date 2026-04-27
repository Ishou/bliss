// Bootstrap for the grid-api's Postgres connection + Flyway migrations
// (ADR-0013 §6, ADR-0009).
//
// Env contract: a single env var `DATABASE_URL` carrying the Postgres URI
// emitted by CNPG's `<cluster>-app` secret, in the form
//   postgres://<user>:<password>@<host>:<port>/<db>
// (alias `postgresql://` accepted). The URI is converted to JDBC at boot
// time so callers never have to think about the wire shape. If
// `DATABASE_URL` is unset we log a structured warning and skip migration —
// this lets `./gradlew test`, `docker compose up`, and any local dev path
// without a Postgres still boot the API. Production sets it via the
// `wordsparrow-api-env` k8s secret (see docs/deploy.md).
package com.bliss.grid.api.infrastructure

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.net.URI
import javax.sql.DataSource

/**
 * Singleton that owns the application's [DataSource] and runs Flyway once at
 * startup. Exposed so infrastructure adapters can obtain the pool without
 * re-parsing `DATABASE_URL`.
 */
object Database {
    private val log = LoggerFactory.getLogger(Database::class.java)

    /** Small pool — the API is read-mostly and Ktor coroutines block briefly. */
    private const val MAX_POOL_SIZE: Int = 5
    private const val POOL_NAME: String = "grid-api-hikari"

    @Volatile
    private var dataSource: HikariDataSource? = null

    /** Active [DataSource], or `null` when [start] ran without `DATABASE_URL`. */
    fun dataSource(): DataSource? = dataSource

    /**
     * Idempotent bootstrap. Reads `DATABASE_URL` from the environment (or
     * a JVM system property of the same name, which the test harness uses
     * to point Hikari at a Testcontainers-managed Postgres). When present,
     * builds a [HikariDataSource] and runs `Flyway.migrate()`. When absent,
     * logs a warning and returns — never throws on missing config so dev
     * boots stay friction-free.
     */
    @Synchronized
    fun start() {
        if (dataSource != null) {
            log.debug("database_already_started pool={}", POOL_NAME)
            return
        }
        val raw = readDatabaseUrl()
        if (raw.isNullOrBlank()) {
            log.warn(
                "database_url_unset action=skip_migration " +
                    "reason=no_DATABASE_URL_env hint=local_dev_or_ci_without_postgres",
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

    /** Test-only — production keeps the pool alive for the JVM's lifetime. */
    @Synchronized
    fun stopForTesting() {
        dataSource?.close()
        dataSource = null
    }

    private fun readDatabaseUrl(): String? = System.getenv("DATABASE_URL") ?: System.getProperty("DATABASE_URL")

    /**
     * Converts `postgres://user:pw@host:port/db?...` (CNPG-style) into the
     * JDBC URL Hikari expects: `jdbc:postgresql://host:port/db?...`. The
     * userinfo is stripped here and re-attached on the [HikariConfig] so
     * the password never appears in logs that echo the URL. If the input
     * already starts with `jdbc:` it is returned unchanged — that supports
     * the Testcontainers happy path where the container hands us a JDBC
     * URL directly.
     */
    internal fun toJdbcUrl(raw: String): String {
        if (raw.startsWith("jdbc:")) return raw
        val uri =
            try {
                URI(raw)
            } catch (e: Exception) {
                throw IllegalStateException("DATABASE_URL is not a valid URI", e)
            }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "postgres" || scheme == "postgresql") {
            "DATABASE_URL must use postgres:// or postgresql:// scheme, got: $scheme"
        }
        val rawHost = requireNotNull(uri.host) { "DATABASE_URL is missing host" }
        // java.net.URI preserves brackets in getHost() on Java 9+ (e.g. "[::1]").
        // Guard both cases: add brackets only when the host is an unbracketed IPv6
        // literal (contains ':' but doesn't start with '[').
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
    internal fun extractCredentials(raw: String): Pair<String?, String?> {
        if (raw.startsWith("jdbc:")) return null to null
        val uri =
            try {
                URI(raw)
            } catch (e: Exception) {
                throw IllegalStateException("DATABASE_URL is not a valid URI", e)
            }
        val userInfo = uri.userInfo ?: return null to null
        val parts = userInfo.split(":", limit = 2)
        val user = parts.getOrNull(0)?.takeIf { it.isNotEmpty() }
        val password = parts.getOrNull(1)
        return user to password
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
        log.info("database_pool_configured pool={} max_pool_size={}", POOL_NAME, MAX_POOL_SIZE)
        return HikariDataSource(config)
    }

    private fun runMigrations(ds: DataSource) {
        val flyway =
            Flyway
                .configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
        val result = flyway.migrate()
        log.info(
            "database_migrations_applied initial_schema_version={} target_schema_version={} migrations_executed={}",
            result.initialSchemaVersion ?: "none",
            result.targetSchemaVersion ?: "none",
            result.migrationsExecuted,
        )
    }

    private const val DEFAULT_POSTGRES_PORT: Int = 5432
}
