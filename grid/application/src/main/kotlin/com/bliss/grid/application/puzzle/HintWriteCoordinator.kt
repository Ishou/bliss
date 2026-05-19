package com.bliss.grid.application.puzzle

import java.sql.Connection
import java.util.UUID

/**
 * Serialises per-user hint writes against concurrent GDPR cascade deletes.
 * The adapter opens a connection, acquires `pg_advisory_xact_lock(user:$userId)`,
 * runs [block] on the locked connection, then commits (or rolls back on throw).
 *
 * The block performs the under-lock fresh cookie re-verify and the
 * use-case call (the latter uses [HintUsageRepository.trySpend] with the
 * provided [Connection] so the lock and the INSERT share one transaction).
 */
interface HintWriteCoordinator {
    suspend fun <T> withUserLock(
        userId: UUID,
        block: suspend (Connection) -> T,
    ): T
}
