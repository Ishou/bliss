package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.application.puzzle.HintUsageRepository
import java.util.UUID
import javax.sql.DataSource

/**
 * Postgres-backed [HintUsageRepository]. Single SQL statement does both the
 * upsert and the cap check; the WHERE on the UPDATE branch + the SELECT on
 * the INSERT branch (`SELECT ... WHERE hintsAllowed > 0`) make a 0-quota
 * puzzle return null without inserting.
 *
 * No transaction wrapper needed — `INSERT ... ON CONFLICT ... DO UPDATE`
 * is single-statement-atomic, and Postgres serializes concurrent updates on
 * the same row via row-level locks (FOR UPDATE inside ON CONFLICT). Two
 * spends at the cap can't both succeed.
 */
class PostgresHintUsageRepository(
    private val dataSource: DataSource,
) : HintUsageRepository {
    override fun trySpend(
        puzzleId: UUID,
        sessionId: UUID,
        hintsAllowed: Int,
    ): Int? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(SQL).use { stmt ->
                stmt.setObject(1, puzzleId)
                stmt.setObject(2, sessionId)
                stmt.setInt(3, hintsAllowed)
                stmt.setInt(4, hintsAllowed)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt("hints_used") else null
                }
            }
        }

    companion object {
        // INSERT side guarded with `WHERE $3 > 0` so a 0-quota puzzle skips
        // the insert; ON CONFLICT side guarded with `hints_used < $4` so a
        // capped row stays put. Either path that doesn't run produces no
        // RETURNING rows → null = exhausted.
        private const val SQL =
            """
            INSERT INTO puzzle_hint_usage (puzzle_id, session_id, hints_used)
            SELECT ?, ?, 1 WHERE ? > 0
            ON CONFLICT (puzzle_id, session_id) DO UPDATE
                SET hints_used = puzzle_hint_usage.hints_used + 1,
                    updated_at = now()
                WHERE puzzle_hint_usage.hints_used < ?
            RETURNING hints_used
            """
    }
}
