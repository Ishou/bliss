package com.bliss.grid.api.infrastructure

import com.bliss.grid.infrastructure.persistence.BlissDatabase
import javax.sql.DataSource

/**
 * Singleton that owns the API's [DataSource] and runs Flyway once at startup.
 * Thin wrapper over [BlissDatabase] (shared with grid-worker) — exists so that
 * api code can keep referring to a single, well-named entry point.
 *
 * Policy: the API tolerates a missing `DATABASE_URL` (logs and skips migration)
 * so dev/CI can boot without Postgres.
 */
object Database {
    private val delegate =
        BlissDatabase(
            poolName = "grid-api-hikari",
            maxPoolSize = 5,
            requireUrl = false,
        )

    fun dataSource(): DataSource? = delegate.dataSource()

    fun start(): Unit = delegate.start()

    fun stop(): Unit = delegate.stop()

    /** Test seam — production callers don't need URL parsing. */
    internal fun toJdbcUrl(raw: String): String = BlissDatabase.toJdbcUrl(raw)

    /** Test seam — production callers don't need URL parsing. */
    internal fun extractCredentials(raw: String): Pair<String?, String?> = BlissDatabase.extractCredentials(raw)
}
