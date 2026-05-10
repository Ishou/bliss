package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.domain.generation.ClueCooldownRepository
import com.bliss.grid.domain.generation.ClueId
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import javax.sql.DataSource

/** Postgres-backed [ClueCooldownRepository] — see ADR-0031 for schema and semantics. */
class PostgresClueCooldownRepository(
    private val dataSource: DataSource,
    private val randomCooldown: (rollMaxInclusive: Int) -> Int =
        { max -> ThreadLocalRandom.current().nextInt(1, max + 1) },
) : ClueCooldownRepository {
    override fun snapshot(bucketId: UUID): ClueCooldownRepository.Snapshot =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val currentSeq = readCurrentSeq(conn, bucketId)
                val active = readActive(conn, bucketId, currentSeq)
                conn.commit()
                ClueCooldownRepository.Snapshot(currentSeq = currentSeq, onCooldown = active)
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            }
        }

    override fun recordGeneration(
        bucketId: UUID,
        usedClues: Collection<ClueId>,
        rollMaxInclusive: Int,
    ): Long {
        require(rollMaxInclusive >= 1) {
            "rollMaxInclusive must be >= 1, was $rollMaxInclusive"
        }
        return dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val newSeq = bumpCounter(conn, bucketId)
                if (usedClues.isNotEmpty()) {
                    upsertCooldownRows(conn, bucketId, usedClues, newSeq, rollMaxInclusive)
                }
                conn.commit()
                newSeq
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            }
        }
    }

    override fun deleteBySession(bucketId: UUID): Int =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val deleted =
                    conn.prepareStatement("DELETE FROM clue_cooldown WHERE session_id = ?").use { stmt ->
                        stmt.setObject(1, bucketId)
                        stmt.executeUpdate()
                    }
                conn.prepareStatement("DELETE FROM clue_cooldown_session WHERE session_id = ?").use { stmt ->
                    stmt.setObject(1, bucketId)
                    stmt.executeUpdate()
                }
                conn.commit()
                deleted
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            }
        }

    private fun readCurrentSeq(
        conn: Connection,
        bucketId: UUID,
    ): Long =
        conn
            .prepareStatement(
                "SELECT generation_seq FROM clue_cooldown_session WHERE session_id = ?",
            ).use { stmt ->
                stmt.setObject(1, bucketId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong("generation_seq") else 0L
                }
            }

    private fun readActive(
        conn: Connection,
        bucketId: UUID,
        currentSeq: Long,
    ): Set<ClueId> =
        conn
            .prepareStatement(
                """
            SELECT word_text, clue_text
            FROM clue_cooldown
            WHERE session_id = ? AND cooldown_until_seq > ?
            """,
            ).use { stmt ->
                stmt.setObject(1, bucketId)
                stmt.setLong(2, currentSeq)
                stmt.executeQuery().use { rs ->
                    val out = HashSet<ClueId>()
                    while (rs.next()) {
                        out += ClueId(rs.getString("word_text"), rs.getString("clue_text"))
                    }
                    out
                }
            }

    private fun bumpCounter(
        conn: Connection,
        bucketId: UUID,
    ): Long =
        conn
            .prepareStatement(
                """
            INSERT INTO clue_cooldown_session (session_id, generation_seq) VALUES (?, 1)
            ON CONFLICT (session_id) DO UPDATE
                SET generation_seq = clue_cooldown_session.generation_seq + 1,
                    updated_at = now()
            RETURNING generation_seq
            """,
            ).use { stmt ->
                stmt.setObject(1, bucketId)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "RETURNING clause must yield a row" }
                    rs.getLong("generation_seq")
                }
            }

    private fun upsertCooldownRows(
        conn: Connection,
        bucketId: UUID,
        usedClues: Collection<ClueId>,
        newSeq: Long,
        rollMaxInclusive: Int,
    ) {
        conn
            .prepareStatement(
                """
            INSERT INTO clue_cooldown
                (session_id, word_text, clue_text, cooldown_until_seq, last_used_seq)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (session_id, word_text, clue_text) DO UPDATE
                SET cooldown_until_seq = EXCLUDED.cooldown_until_seq,
                    last_used_seq      = EXCLUDED.last_used_seq,
                    updated_at         = now()
            """,
            ).use { stmt ->
                for (clue in usedClues) {
                    val n = randomCooldown(rollMaxInclusive)
                    stmt.setObject(1, bucketId)
                    stmt.setString(2, clue.wordText)
                    stmt.setString(3, clue.clueText)
                    stmt.setLong(4, newSeq + n)
                    stmt.setLong(5, newSeq)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
    }
}
