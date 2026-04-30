package com.bliss.grid.worker.db

import com.bliss.grid.infrastructure.persistence.BlissDatabase
import javax.sql.DataSource

/**
 * Worker-side bootstrap. Thin wrapper over [BlissDatabase] (shared with grid-api).
 *
 * Policy: the worker has no no-DB mode — `DATABASE_URL` is required (ADR-0013 §6, §7).
 */
object Database {
    private val delegate = BlissDatabase(
        poolName = "grid-worker-hikari",
        maxPoolSize = 4,
        requireUrl = true,
    )

    fun dataSource(): DataSource? = delegate.dataSource()

    fun start(): Unit = delegate.start()

    fun stopForTesting(): Unit = delegate.stopForTesting()
}
