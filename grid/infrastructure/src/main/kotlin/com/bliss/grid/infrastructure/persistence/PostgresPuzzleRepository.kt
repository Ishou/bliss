package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.application.puzzle.PuzzleRepository
import com.bliss.grid.application.puzzle.StoredPuzzle
import com.bliss.grid.application.puzzle.StoredSummary
import kotlinx.serialization.json.Json
import org.postgresql.util.PGobject
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

/**
 * Postgres-backed [PuzzleRepository]. Single-row reads on hit, two
 * statements on miss (factory + INSERT-or-SELECT). The "INSERT ... ON
 * CONFLICT DO NOTHING; SELECT" idiom serves as the canonical race-free
 * getOrCompute under low contention — single-replica posture, no advisory
 * locks needed at this scale.
 *
 * Puzzle JSON shape lives in [PuzzlePayload]; this class wraps it with the
 * row-level metadata (title, language, hints_allowed, created_at).
 */
class PostgresPuzzleRepository(
    private val dataSource: DataSource,
    private val json: Json =
        Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
        },
) : PuzzleRepository {
    override fun get(puzzleId: UUID): StoredPuzzle? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(SELECT_SQL).use { stmt ->
                stmt.setObject(1, puzzleId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toStoredPuzzle() else null
                }
            }
        }

    override fun getOrCompute(
        puzzleId: UUID,
        factory: () -> StoredPuzzle?,
    ): StoredPuzzle? {
        get(puzzleId)?.let { return it }
        val produced = factory() ?: return null
        dataSource.connection.use { conn ->
            conn.prepareStatement(INSERT_SQL).use { stmt ->
                stmt.setObject(1, puzzleId)
                stmt.setInt(2, produced.grid.width)
                stmt.setInt(3, produced.grid.height)
                stmt.setString(4, produced.language)
                stmt.setString(5, produced.title)
                stmt.setObject(6, jsonbOf(produced))
                stmt.setInt(7, produced.hintsAllowed)
                stmt.setTimestamp(8, Timestamp.from(produced.createdAt))
                stmt.setInt(9, produced.totalLetterCells)
                stmt.executeUpdate()
            }
        }
        // A concurrent inserter may have won the race — read back the
        // canonical row so all callers observe identical state.
        return get(puzzleId) ?: produced
    }

    override fun findSummariesByIds(puzzleIds: List<UUID>): List<StoredSummary> {
        if (puzzleIds.isEmpty()) return emptyList()
        return dataSource.connection.use { conn ->
            val arr = conn.createArrayOf("uuid", puzzleIds.toTypedArray())
            conn.prepareStatement(SUMMARIES_SQL).use { stmt ->
                stmt.setArray(1, arr)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                StoredSummary(
                                    puzzleId = rs.getObject("puzzle_id", UUID::class.java),
                                    totalLetterCells = rs.getInt("total_letter_cells"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun jsonbOf(stored: StoredPuzzle): PGobject =
        PGobject().apply {
            type = "jsonb"
            value = json.encodeToString(PuzzlePayload.serializer(), PuzzlePayload.fromGrid(stored.grid))
        }

    private fun ResultSet.toStoredPuzzle(): StoredPuzzle {
        val payloadJson = getString("payload")
        val payload = json.decodeFromString(PuzzlePayload.serializer(), payloadJson)
        return StoredPuzzle(
            grid = payload.toGrid(),
            title = getString("title"),
            language = getString("language"),
            hintsAllowed = getInt("hints_allowed"),
            createdAt = getTimestamp("created_at").toInstant(),
        )
    }

    companion object {
        private const val SELECT_SQL =
            "SELECT puzzle_id, width, height, language, title, payload, hints_allowed, created_at, " +
                "total_letter_cells " +
                "FROM puzzles WHERE puzzle_id = ?"

        private const val INSERT_SQL =
            "INSERT INTO puzzles " +
                "(puzzle_id, width, height, language, title, payload, hints_allowed, created_at, " +
                "total_letter_cells) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (puzzle_id) DO NOTHING"

        private const val SUMMARIES_SQL =
            "SELECT puzzle_id, total_letter_cells " +
                "FROM puzzles " +
                "WHERE puzzle_id = ANY(?) AND total_letter_cells IS NOT NULL"
    }
}
