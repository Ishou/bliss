package com.bliss.game.infrastructure.persistence

import com.bliss.game.application.lobby.LobbyWriteCoordinator
import com.bliss.game.domain.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource

/** Postgres-backed [LobbyWriteCoordinator]; opens a pooled connection, acquires an advisory lock, runs [block], then commits or rolls back. */
class PostgresLobbyWriteCoordinator(
    private val dataSource: DataSource,
) : LobbyWriteCoordinator {
    override suspend fun <T> withUserLock(
        userId: UserId,
        block: suspend (Connection) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val previousAutoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    conn.prepareStatement(LOCK_SQL).use { stmt ->
                        stmt.setString(1, "user:${userId.value}")
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
