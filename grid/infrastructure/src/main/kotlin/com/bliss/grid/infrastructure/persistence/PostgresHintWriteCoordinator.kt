package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.application.puzzle.HintWriteCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Postgres-backed [HintWriteCoordinator]. Opens a pooled connection,
 * sets autoCommit off, takes `pg_advisory_xact_lock(hashtext('user:' || userId))`,
 * runs [block] with the locked connection, then commits — or rolls back
 * on any throw. The advisory lock releases on commit/rollback.
 */
class PostgresHintWriteCoordinator(
    private val dataSource: DataSource,
) : HintWriteCoordinator {
    override suspend fun <T> withUserLock(
        userId: UUID,
        block: suspend (Connection) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val previousAutoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    conn.prepareStatement(LOCK_SQL).use { stmt ->
                        stmt.setString(1, "user:$userId")
                        stmt.execute()
                    }
                    val result = block(conn)
                    conn.commit()
                    result
                } catch (cause: Throwable) {
                    conn.rollback()
                    throw cause
                } finally {
                    conn.autoCommit = previousAutoCommit
                }
            }
        }

    companion object {
        private const val LOCK_SQL = "SELECT pg_advisory_xact_lock(hashtext(?))"
    }
}
