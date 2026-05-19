package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.application.puzzle.HintUsageRepository
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Postgres-backed [HintUsageRepository]. The single-statement upsert
 * (`INSERT ... ON CONFLICT ... DO UPDATE`) keeps the spend atomic; the
 * write path passes the locked [Connection] from the per-user advisory
 * lock so spend + lock + under-lock fresh re-verify share one transaction.
 */
class PostgresHintUsageRepository(
    private val dataSource: DataSource,
) : HintUsageRepository {
    override fun trySpend(
        conn: Connection,
        puzzleId: UUID,
        userId: UUID,
        hintsAllowed: Int,
    ): Int? =
        conn.prepareStatement(SPEND_SQL).use { stmt ->
            stmt.setObject(1, puzzleId)
            stmt.setObject(2, userId)
            stmt.setInt(3, hintsAllowed)
            stmt.setInt(4, hintsAllowed)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getInt("hints_used") else null
            }
        }

    override fun usedFor(
        puzzleId: UUID,
        userId: UUID,
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(USED_FOR_SQL).use { stmt ->
                stmt.setObject(1, puzzleId)
                stmt.setObject(2, userId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }

    override fun deleteByUser(userId: UUID): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(DELETE_BY_USER_SQL).use { stmt ->
                stmt.setObject(1, userId)
                stmt.executeUpdate()
            }
        }

    companion object {
        private const val SPEND_SQL =
            """
            INSERT INTO puzzle_hint_usage (puzzle_id, user_id, hints_used)
            SELECT ?, ?, 1 WHERE ? > 0
            ON CONFLICT (puzzle_id, user_id) DO UPDATE
                SET hints_used = puzzle_hint_usage.hints_used + 1,
                    updated_at = now()
                WHERE puzzle_hint_usage.hints_used < ?
            RETURNING hints_used
            """

        private const val USED_FOR_SQL =
            "SELECT hints_used FROM puzzle_hint_usage WHERE puzzle_id = ? AND user_id = ?"

        private const val DELETE_BY_USER_SQL =
            "DELETE FROM puzzle_hint_usage WHERE user_id = ?"
    }
}
