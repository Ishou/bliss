package com.bliss.game.infrastructure.persistence

import com.bliss.game.application.ports.EraseSessionResult
import com.bliss.game.application.ports.LobbyRepository
import com.bliss.game.domain.BlockCell
import com.bliss.game.domain.CellEntry
import com.bliss.game.domain.DefinitionCell
import com.bliss.game.domain.GameArrow
import com.bliss.game.domain.GameCell
import com.bliss.game.domain.GameClue
import com.bliss.game.domain.GameClueDirection
import com.bliss.game.domain.GameDefinitionClue
import com.bliss.game.domain.GamePuzzle
import com.bliss.game.domain.GameSession
import com.bliss.game.domain.GridConfig
import com.bliss.game.domain.Letter
import com.bliss.game.domain.LetterCell
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyCode
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.LobbyLifecycleState
import com.bliss.game.domain.LobbyTitle
import com.bliss.game.domain.Player
import com.bliss.game.domain.Position
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.SessionId
import com.bliss.game.domain.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import org.postgresql.util.PGobject
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Postgres-backed [LobbyRepository]. JDBC via Hikari. The atomicity contract
 * the in-memory adapter promises with a per-lobby [java.util.concurrent.locks.ReentrantLock]
 * is preserved here via `SELECT ... FOR UPDATE` inside a transaction, per ADR-0039.
 *
 * Children (`lobby_players`, `lobby_cell_entries`) are full-rewritten on every
 * save/mutate. Lobbies cap at 8 players and a bounded grid size, so the
 * DELETE+INSERT pattern is cheap and avoids the diff-and-upsert complexity
 * that JDBC without an ORM would otherwise require.
 *
 * `mutate(id, mutator)` semantics:
 *   - mutator returns Lobby → UPDATE parent + rewrite children, return new state.
 *   - mutator returns null   → DELETE the lobby (children cascade), return null.
 *   - mutator throws         → ROLLBACK and rethrow.
 *
 * `game_payload` JSONB stores the GameSession projection sans entries; entries
 * live in `lobby_cell_entries` for join-and-hydrate.
 */
class PostgresLobbyRepository(
    private val ds: DataSource,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        },
) : LobbyRepository {
    override suspend fun findById(id: LobbyId): Lobby? =
        withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                loadLobby(conn, id)
            }
        }

    override suspend fun findByCode(code: LobbyCode): Lobby? =
        withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                val id = selectIdByCode(conn, code) ?: return@withContext null
                loadLobby(conn, id)
            }
        }

    // Filters to IN_PROGRESS + COMPLETED so WAITING (un-started) lobbies do not
    // appear in the "Mes parties" listing — see ADR-0039 amendment 2026-05-12.
    // WAITING lobbies remain reachable via direct URL / invite code.
    override suspend fun findBySessionId(sessionId: SessionId): List<Lobby> =
        withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                val ids = mutableListOf<LobbyId>()
                // owner arm keeps lobby visible after leave-grace drops owner from lobby_players (ADR-0039).
                conn
                    .prepareStatement(
                        "SELECT l.id FROM lobbies l " +
                            "WHERE (l.owner_session_id = ? OR EXISTS (" +
                            "  SELECT 1 FROM lobby_players lp " +
                            "  WHERE lp.lobby_id = l.id AND lp.session_id = ?" +
                            ")) " +
                            "AND l.state IN ('IN_PROGRESS', 'COMPLETED') " +
                            "ORDER BY l.last_activity_at DESC",
                    ).use { ps ->
                        val sidUuid = UUID.fromString(sessionId.value)
                        ps.setObject(1, sidUuid)
                        ps.setObject(2, sidUuid)
                        ps.executeQuery().use { rs ->
                            while (rs.next()) ids += LobbyId(rs.getString("id"))
                        }
                    }
                ids.mapNotNull { loadLobby(conn, it) }
            }
        }

    override suspend fun save(lobby: Lobby): Lobby =
        withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                conn.autoCommit = false
                try {
                    upsertLobby(conn, lobby)
                    rewriteChildren(conn, lobby)
                    conn.commit()
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = true
                }
                lobby
            }
        }

    override suspend fun mutate(
        id: LobbyId,
        mutator: (Lobby) -> Lobby?,
    ): Lobby? =
        withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                conn.autoCommit = false
                try {
                    val current =
                        lockAndLoad(conn, id) ?: run {
                            conn.rollback()
                            return@withContext null
                        }
                    val next = mutator(current)
                    if (next == null) {
                        deleteLobbyRow(conn, id)
                        conn.commit()
                        null
                    } else {
                        upsertLobby(conn, next)
                        rewriteChildren(conn, next)
                        conn.commit()
                        next
                    }
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = true
                }
            }
        }

    override suspend fun delete(id: LobbyId) {
        withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                deleteLobbyRow(conn, id)
            }
        }
    }

    /**
     * RGPD Article 17 erasure (ADR-0039). Single transaction, all affected
     * lobbies locked upfront with `SELECT ... FOR UPDATE` ordered by id to
     * prevent dead-locks against concurrent eraseSession or mutate calls.
     *
     * The three rules applied per lobby:
     *  1. Owner + sole player → DELETE the row (children cascade).
     *  2. Owner + others      → UPDATE owner_session_id to the earliest-
     *                           joined remaining player, DELETE the playership,
     *                           NULL out cell-entry attribution (anonymise).
     *  3. Non-owner           → DELETE the playership, NULL out attribution.
     */
    override suspend fun eraseSession(sessionId: SessionId): EraseSessionResult =
        withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                conn.autoCommit = false
                try {
                    val result = eraseInTransaction(conn, sessionId)
                    conn.commit()
                    result
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = true
                }
            }
        }

    private fun eraseInTransaction(
        conn: Connection,
        sessionId: SessionId,
    ): EraseSessionResult {
        val sessionUuid = UUID.fromString(sessionId.value)
        // Snapshot + lock the set of affected lobbies in deterministic order
        // so two concurrent erasures (different sessionIds touching overlapping
        // lobbies) cannot deadlock by acquiring locks in opposite orders.
        val lobbyIds = mutableListOf<LobbyId>()
        conn
            .prepareStatement(
                "SELECT l.id FROM lobbies l " +
                    "JOIN lobby_players lp ON lp.lobby_id = l.id " +
                    "WHERE lp.session_id = ? " +
                    "ORDER BY l.id " +
                    "FOR UPDATE OF l",
            ).use { ps ->
                ps.setObject(1, sessionUuid)
                ps.executeQuery().use { rs ->
                    while (rs.next()) lobbyIds += LobbyId(rs.getString("id"))
                }
            }
        if (lobbyIds.isEmpty()) return EraseSessionResult.Empty

        var deletedLobbies = 0
        var transferredLobbies = 0
        var removedPlayerships = 0
        var anonymisedEntries = 0
        for (id in lobbyIds) {
            // Read current state under the row lock acquired above.
            val (owner, remainingPlayers) = loadOwnerAndRemainingPlayers(conn, id, sessionUuid)
            if (owner == null) continue // lobby vanished between SELECT and now (impossible inside this tx, but safe).
            val ownsLobby = owner == sessionUuid
            if (ownsLobby && remainingPlayers.isEmpty()) {
                // Rule 1: cascade-delete.
                conn.prepareStatement("DELETE FROM lobbies WHERE id = ?").use { ps ->
                    ps.setString(1, id.value)
                    ps.executeUpdate()
                }
                deletedLobbies += 1
                continue
            }
            // Rules 2 + 3: remove the playership.
            val playerRowsDeleted =
                conn
                    .prepareStatement(
                        "DELETE FROM lobby_players WHERE lobby_id = ? AND session_id = ?",
                    ).use { ps ->
                        ps.setString(1, id.value)
                        ps.setObject(2, sessionUuid)
                        ps.executeUpdate()
                    }
            removedPlayerships += playerRowsDeleted
            // Anonymise this session's cell entries: count + NULL out the FK in one statement.
            anonymisedEntries +=
                conn
                    .prepareStatement(
                        "UPDATE lobby_cell_entries SET written_by_session_id = NULL " +
                            "WHERE lobby_id = ? AND written_by_session_id = ?",
                    ).use { ps ->
                        ps.setString(1, id.value)
                        ps.setObject(2, sessionUuid)
                        ps.executeUpdate()
                    }
            // Rule 2: transfer ownership to earliest-joined remaining player.
            if (ownsLobby) {
                val newOwner = remainingPlayers.minBy { it.second }.first
                conn
                    .prepareStatement("UPDATE lobbies SET owner_session_id = ? WHERE id = ?")
                    .use { ps ->
                        ps.setObject(1, newOwner)
                        ps.setString(2, id.value)
                        ps.executeUpdate()
                    }
                transferredLobbies += 1
            }
        }
        return EraseSessionResult(deletedLobbies, transferredLobbies, removedPlayerships, anonymisedEntries)
    }

    /**
     * Returns (ownerSessionUuid, remainingPlayers) where `remainingPlayers` is
     * the list of (sessionId, joinedAt) pairs for every player except [target].
     */
    private fun loadOwnerAndRemainingPlayers(
        conn: Connection,
        id: LobbyId,
        target: UUID,
    ): Pair<UUID?, List<Pair<UUID, Instant>>> {
        val owner =
            conn
                .prepareStatement("SELECT owner_session_id FROM lobbies WHERE id = ?")
                .use { ps ->
                    ps.setString(1, id.value)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getObject("owner_session_id", UUID::class.java) else null
                    }
                } ?: return null to emptyList()
        val remaining = mutableListOf<Pair<UUID, Instant>>()
        conn
            .prepareStatement(
                "SELECT session_id, joined_at FROM lobby_players " +
                    "WHERE lobby_id = ? AND session_id <> ?",
            ).use { ps ->
                ps.setString(1, id.value)
                ps.setObject(2, target)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val sid = rs.getObject("session_id", UUID::class.java)
                        val joined = rs.getTimestamp("joined_at").toInstant()
                        remaining += sid to joined
                    }
                }
            }
        return owner to remaining
    }

    override suspend fun findWaitingByOwnerSession(ownerSessionId: SessionId): Lobby? =
        withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                val id =
                    conn
                        .prepareStatement(
                            "SELECT id FROM lobbies " +
                                "WHERE state = 'WAITING' AND owner_session_id = ? LIMIT 1",
                        ).use { ps ->
                            ps.setObject(1, UUID.fromString(ownerSessionId.value))
                            ps.executeQuery().use { rs ->
                                if (rs.next()) LobbyId(rs.getString("id")) else null
                            }
                        } ?: return@withContext null
                loadLobby(conn, id)
            }
        }

    override suspend fun findIdleWaiting(cutoff: Instant): List<Lobby> =
        withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                val ids = mutableListOf<LobbyId>()
                conn
                    .prepareStatement(
                        "SELECT id FROM lobbies " +
                            "WHERE state = 'WAITING' AND last_activity_at <= ?",
                    ).use { ps ->
                        ps.setTimestamp(1, Timestamp.from(cutoff))
                        ps.executeQuery().use { rs ->
                            while (rs.next()) ids += LobbyId(rs.getString("id"))
                        }
                    }
                ids.mapNotNull { loadLobby(conn, it) }
            }
        }

    override suspend fun findIdleCompleted(cutoff: Instant): List<Lobby> =
        withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                val ids = mutableListOf<LobbyId>()
                conn
                    .prepareStatement(
                        "SELECT id FROM lobbies " +
                            "WHERE state = 'COMPLETED' AND last_activity_at <= ?",
                    ).use { ps ->
                        ps.setTimestamp(1, Timestamp.from(cutoff))
                        ps.executeQuery().use { rs ->
                            while (rs.next()) ids += LobbyId(rs.getString("id"))
                        }
                    }
                ids.mapNotNull { loadLobby(conn, it) }
            }
        }

    // UPDATE … RETURNING collects touched lobby ids in one round-trip; matches in-memory adapter's atomic per-player lock.
    override suspend fun rebindAnonSeats(
        anonSessionId: SessionId,
        userId: UserId,
        newPseudonym: Pseudonym,
    ): Set<LobbyId> =
        withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                val touched = mutableSetOf<LobbyId>()
                conn
                    .prepareStatement(
                        "UPDATE lobby_players SET user_id = ?, pseudonym = ? " +
                            "WHERE session_id = ? AND user_id IS NULL " +
                            "RETURNING lobby_id",
                    ).use { ps ->
                        ps.setObject(1, UUID.fromString(userId.value))
                        ps.setString(2, newPseudonym.value)
                        ps.setObject(3, UUID.fromString(anonSessionId.value))
                        ps.executeQuery().use { rs ->
                            while (rs.next()) touched += LobbyId(rs.getString("lobby_id"))
                        }
                    }
                touched
            }
        }

    override suspend fun unbindUserSeats(
        userId: UserId,
        anonPseudonym: Pseudonym,
    ): Set<LobbyId> =
        withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                val touched = mutableSetOf<LobbyId>()
                conn
                    .prepareStatement(
                        "UPDATE lobby_players SET user_id = NULL, pseudonym = ? " +
                            "WHERE user_id = ? " +
                            "RETURNING lobby_id",
                    ).use { ps ->
                        ps.setString(1, anonPseudonym.value)
                        ps.setObject(2, UUID.fromString(userId.value))
                        ps.executeQuery().use { rs ->
                            while (rs.next()) touched += LobbyId(rs.getString("lobby_id"))
                        }
                    }
                touched
            }
        }

    // ADR-0049 user.deleted: NULL out the FK and replace pseudonym in one statement so a
    // retried event hits the same idempotent shape as the in-memory adapter.
    override suspend fun anonymizeUserSeats(
        userId: UserId,
        replacementPseudonym: Pseudonym,
    ): Set<LobbyId> =
        withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                val touched = mutableSetOf<LobbyId>()
                conn
                    .prepareStatement(
                        "UPDATE lobby_players SET user_id = NULL, pseudonym = ? " +
                            "WHERE user_id = ? " +
                            "RETURNING lobby_id",
                    ).use { ps ->
                        ps.setString(1, replacementPseudonym.value)
                        ps.setObject(2, UUID.fromString(userId.value))
                        ps.executeQuery().use { rs ->
                            while (rs.next()) touched += LobbyId(rs.getString("lobby_id"))
                        }
                    }
                touched
            }
        }

    // ADR-0049 user.renamed: refresh pseudonym only, leave user_id in place. WHERE clause
    // excludes rows already on the new pseudonym so a redelivered event returns an empty set.
    override suspend fun refreshUserPseudonym(
        userId: UserId,
        newPseudonym: Pseudonym,
    ): Set<LobbyId> =
        withContext(Dispatchers.IO) {
            ds.connection.use { conn ->
                val touched = mutableSetOf<LobbyId>()
                conn
                    .prepareStatement(
                        "UPDATE lobby_players SET pseudonym = ? " +
                            "WHERE user_id = ? AND pseudonym <> ? " +
                            "RETURNING lobby_id",
                    ).use { ps ->
                        ps.setString(1, newPseudonym.value)
                        ps.setObject(2, UUID.fromString(userId.value))
                        ps.setString(3, newPseudonym.value)
                        ps.executeQuery().use { rs ->
                            while (rs.next()) touched += LobbyId(rs.getString("lobby_id"))
                        }
                    }
                touched
            }
        }

    // ---- internals -----------------------------------------------------

    private fun selectIdByCode(
        conn: Connection,
        code: LobbyCode,
    ): LobbyId? =
        conn.prepareStatement("SELECT id FROM lobbies WHERE code = ?").use { ps ->
            ps.setString(1, code.value)
            ps.executeQuery().use { rs ->
                if (rs.next()) LobbyId(rs.getString("id")) else null
            }
        }

    private fun loadLobby(
        conn: Connection,
        id: LobbyId,
    ): Lobby? =
        conn
            .prepareStatement(
                "SELECT id, code, owner_session_id, state, grid_width, grid_height, " +
                    "title, game_payload, last_activity_at, completed_at " +
                    "FROM lobbies WHERE id = ?",
            ).use { ps ->
                ps.setString(1, id.value)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) null else hydrate(conn, rs)
                }
            }

    private fun lockAndLoad(
        conn: Connection,
        id: LobbyId,
    ): Lobby? =
        conn
            .prepareStatement(
                "SELECT id, code, owner_session_id, state, grid_width, grid_height, " +
                    "title, game_payload, last_activity_at, completed_at " +
                    "FROM lobbies WHERE id = ? FOR UPDATE",
            ).use { ps ->
                ps.setString(1, id.value)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) null else hydrate(conn, rs)
                }
            }

    private fun hydrate(
        conn: Connection,
        rs: ResultSet,
    ): Lobby {
        val id = LobbyId(rs.getString("id"))
        val players = loadPlayers(conn, id)
        val entries = loadEntries(conn, id)
        val payloadJson = rs.getString("game_payload")
        val game = payloadJson?.let { decodeGameSession(it, entries) }
        return Lobby(
            id = id,
            code = LobbyCode(rs.getString("code")),
            ownerSessionId = SessionId(rs.getObject("owner_session_id", UUID::class.java).toString()),
            state = LobbyLifecycleState.valueOf(rs.getString("state")),
            gridConfig = GridConfig(rs.getInt("grid_width"), rs.getInt("grid_height")),
            title = rs.getString("title")?.let { LobbyTitle(it) },
            players = players,
            game = game,
            lastActivityAt = rs.getTimestamp("last_activity_at").toInstant(),
        )
    }

    private fun loadPlayers(
        conn: Connection,
        id: LobbyId,
    ): Map<SessionId, Player> {
        val out = LinkedHashMap<SessionId, Player>()
        conn
            .prepareStatement(
                "SELECT session_id, pseudonym, joined_at, user_id FROM lobby_players " +
                    "WHERE lobby_id = ? ORDER BY joined_at ASC",
            ).use { ps ->
                ps.setString(1, id.value)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val sid = SessionId(rs.getObject("session_id", UUID::class.java).toString())
                        val rawUserId = rs.getObject("user_id", UUID::class.java)
                        out[sid] =
                            Player(
                                sessionId = sid,
                                pseudonym = Pseudonym(rs.getString("pseudonym")),
                                joinedAt = rs.getTimestamp("joined_at").toInstant(),
                                userId = rawUserId?.let { UserId(it.toString()) },
                            )
                    }
                }
            }
        return out
    }

    private fun loadEntries(
        conn: Connection,
        id: LobbyId,
    ): Map<Position, CellEntry> {
        val out = HashMap<Position, CellEntry>()
        conn
            .prepareStatement(
                "SELECT row, col, letter, written_by_session_id, written_at " +
                    "FROM lobby_cell_entries WHERE lobby_id = ?",
            ).use { ps ->
                ps.setString(1, id.value)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val pos = Position(rs.getInt("row"), rs.getInt("col"))
                        val raw = rs.getObject("written_by_session_id", UUID::class.java)
                        // RGPD-anonymised entries (null in the DB per ADR-0039) are
                        // surfaced as the domain SessionId.ANON sentinel so the
                        // existing CellEntry shape (which requires a SessionId)
                        // keeps working. rewriteChildren maps ANON back to NULL
                        // on save so the database column itself stays anonymised.
                        val sid = if (raw == null) SessionId.ANON else SessionId(raw.toString())
                        out[pos] =
                            CellEntry(
                                sessionId = sid,
                                letter = Letter(rs.getString("letter").first()),
                                writtenAt = rs.getTimestamp("written_at").toInstant(),
                            )
                    }
                }
            }
        return out
    }

    private fun upsertLobby(
        conn: Connection,
        lobby: Lobby,
    ) {
        conn
            .prepareStatement(
                """
                INSERT INTO lobbies
                  (id, code, owner_session_id, state, grid_width, grid_height,
                   title, game_payload, last_activity_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                  code             = EXCLUDED.code,
                  owner_session_id = EXCLUDED.owner_session_id,
                  state            = EXCLUDED.state,
                  grid_width       = EXCLUDED.grid_width,
                  grid_height      = EXCLUDED.grid_height,
                  title            = EXCLUDED.title,
                  game_payload     = EXCLUDED.game_payload,
                  last_activity_at = EXCLUDED.last_activity_at,
                  completed_at     = EXCLUDED.completed_at
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, lobby.id.value)
                ps.setString(2, lobby.code.value)
                ps.setObject(3, UUID.fromString(lobby.ownerSessionId.value))
                ps.setString(4, lobby.state.name)
                ps.setInt(5, lobby.gridConfig.width)
                ps.setInt(6, lobby.gridConfig.height)
                val title = lobby.title
                if (title != null) ps.setString(7, title.value) else ps.setNull(7, java.sql.Types.VARCHAR)
                val game = lobby.game
                if (game != null) ps.setObject(8, jsonbOf(game)) else ps.setNull(8, java.sql.Types.OTHER)
                ps.setTimestamp(9, Timestamp.from(lobby.lastActivityAt))
                val completedAt = game?.completedAt
                if (completedAt != null) ps.setTimestamp(10, Timestamp.from(completedAt)) else ps.setNull(10, java.sql.Types.TIMESTAMP)
                ps.executeUpdate()
            }
    }

    private fun rewriteChildren(
        conn: Connection,
        lobby: Lobby,
    ) {
        conn.prepareStatement("DELETE FROM lobby_players WHERE lobby_id = ?").use { ps ->
            ps.setString(1, lobby.id.value)
            ps.executeUpdate()
        }
        if (lobby.players.isNotEmpty()) {
            conn
                .prepareStatement(
                    "INSERT INTO lobby_players (lobby_id, session_id, pseudonym, joined_at, user_id) " +
                        "VALUES (?, ?, ?, ?, ?)",
                ).use { ps ->
                    for (p in lobby.players.values) {
                        ps.setString(1, lobby.id.value)
                        ps.setObject(2, UUID.fromString(p.sessionId.value))
                        ps.setString(3, p.pseudonym.value)
                        ps.setTimestamp(4, Timestamp.from(p.joinedAt))
                        val uid = p.userId
                        if (uid != null) {
                            ps.setObject(5, UUID.fromString(uid.value))
                        } else {
                            ps.setNull(5, java.sql.Types.OTHER)
                        }
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
        }
        conn.prepareStatement("DELETE FROM lobby_cell_entries WHERE lobby_id = ?").use { ps ->
            ps.setString(1, lobby.id.value)
            ps.executeUpdate()
        }
        val entries = lobby.game?.entries
        if (!entries.isNullOrEmpty()) {
            conn
                .prepareStatement(
                    "INSERT INTO lobby_cell_entries " +
                        "(lobby_id, row, col, letter, written_by_session_id, written_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                ).use { ps ->
                    for ((pos, entry) in entries) {
                        ps.setString(1, lobby.id.value)
                        ps.setInt(2, pos.row)
                        ps.setInt(3, pos.column)
                        ps.setString(4, entry.letter.value.toString())
                        if (entry.sessionId == SessionId.ANON) {
                            ps.setNull(5, java.sql.Types.OTHER)
                        } else {
                            ps.setObject(5, UUID.fromString(entry.sessionId.value))
                        }
                        ps.setTimestamp(6, Timestamp.from(entry.writtenAt))
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
        }
    }

    private fun deleteLobbyRow(
        conn: Connection,
        id: LobbyId,
    ) {
        conn.prepareStatement("DELETE FROM lobbies WHERE id = ?").use { ps ->
            ps.setString(1, id.value)
            ps.executeUpdate()
        }
    }

    private fun jsonbOf(game: GameSession): PGobject =
        PGobject().apply {
            type = "jsonb"
            value = json.encodeToString(GameSessionPayload.serializer(), GameSessionPayload.from(game))
        }

    private fun decodeGameSession(
        raw: String,
        entries: Map<Position, CellEntry>,
    ): GameSession = json.decodeFromString(GameSessionPayload.serializer(), raw).toDomain(entries)
}

/**
 * JSONB-friendly projection of [GameSession] minus `entries`. Entries are stored
 * normalised in `lobby_cell_entries` so resume hydration is a plain join.
 */
@Serializable
private data class GameSessionPayload(
    val puzzle: PuzzlePayload,
    val startedAt: String,
    val completedAt: String? = null,
    val lockedPositions: List<PositionPayload> = emptyList(),
) {
    fun toDomain(entries: Map<Position, CellEntry>): GameSession =
        GameSession(
            puzzle = puzzle.toDomain(),
            entries = entries,
            startedAt = Instant.parse(startedAt),
            completedAt = completedAt?.let { Instant.parse(it) },
            lockedPositions = lockedPositions.map { it.toDomain() }.toSet(),
        )

    companion object {
        fun from(game: GameSession): GameSessionPayload =
            GameSessionPayload(
                puzzle = PuzzlePayload.from(game.puzzle),
                startedAt = game.startedAt.toString(),
                completedAt = game.completedAt?.toString(),
                lockedPositions = game.lockedPositions.map { PositionPayload(it.row, it.column) },
            )
    }
}

@Serializable
private data class PositionPayload(
    val row: Int,
    val column: Int,
) {
    fun toDomain(): Position = Position(row, column)
}

@Serializable
private data class PuzzlePayload(
    val id: String,
    val title: String,
    val language: String,
    val width: Int,
    val height: Int,
    val cells: List<CellPayload>,
    val clues: List<CluePayload>,
    val createdAt: String,
) {
    fun toDomain(): GamePuzzle =
        GamePuzzle(
            id = UUID.fromString(id),
            title = title,
            language = language,
            width = width,
            height = height,
            cells = cells.map { it.toDomain() },
            clues = clues.map { it.toDomain() },
            createdAt = Instant.parse(createdAt),
        )

    companion object {
        fun from(puzzle: GamePuzzle): PuzzlePayload =
            PuzzlePayload(
                id = puzzle.id.toString(),
                title = puzzle.title,
                language = puzzle.language,
                width = puzzle.width,
                height = puzzle.height,
                cells = puzzle.cells.map { CellPayload.from(it) },
                clues = puzzle.clues.map { CluePayload.from(it) },
                createdAt = puzzle.createdAt.toString(),
            )
    }
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
private sealed class CellPayload {
    abstract fun toDomain(): GameCell

    @Serializable
    @SerialName("letter")
    data class LetterPayload(
        val row: Int,
        val column: Int,
        val answer: String? = null,
    ) : CellPayload() {
        override fun toDomain(): GameCell =
            LetterCell(
                position = Position(row, column),
                answer = answer?.firstOrNull()?.let { Letter(it) },
            )
    }

    @Serializable
    @SerialName("definition")
    data class DefinitionPayload(
        val row: Int,
        val column: Int,
        val clues: List<DefinitionCluePayload>,
    ) : CellPayload() {
        override fun toDomain(): GameCell =
            DefinitionCell(
                position = Position(row, column),
                clues =
                    clues.map {
                        GameDefinitionClue(
                            id = UUID.fromString(it.id),
                            text = it.text,
                            arrow = GameArrow.valueOf(it.arrow),
                        )
                    },
            )
    }

    @Serializable
    @SerialName("block")
    data class BlockPayload(
        val row: Int,
        val column: Int,
    ) : CellPayload() {
        override fun toDomain(): GameCell = BlockCell(position = Position(row, column))
    }

    companion object {
        fun from(cell: GameCell): CellPayload =
            when (cell) {
                is LetterCell ->
                    LetterPayload(
                        row = cell.position.row,
                        column = cell.position.column,
                        answer = cell.answer?.value?.toString(),
                    )
                is DefinitionCell ->
                    DefinitionPayload(
                        row = cell.position.row,
                        column = cell.position.column,
                        clues =
                            cell.clues.map {
                                DefinitionCluePayload(
                                    id = it.id.toString(),
                                    text = it.text,
                                    arrow = it.arrow.name,
                                )
                            },
                    )
                is BlockCell ->
                    BlockPayload(
                        row = cell.position.row,
                        column = cell.position.column,
                    )
            }
    }
}

@Serializable
private data class DefinitionCluePayload(
    val id: String,
    val text: String,
    val arrow: String,
)

@Serializable
private data class CluePayload(
    val id: String,
    val direction: String,
    val startRow: Int,
    val startColumn: Int,
    val length: Int,
    val text: String,
) {
    fun toDomain(): GameClue =
        GameClue(
            id = UUID.fromString(id),
            direction = GameClueDirection.valueOf(direction),
            start = Position(startRow, startColumn),
            length = length,
            text = text,
        )

    companion object {
        fun from(clue: GameClue): CluePayload =
            CluePayload(
                id = clue.id.toString(),
                direction = clue.direction.name,
                startRow = clue.start.row,
                startColumn = clue.start.column,
                length = clue.length,
                text = clue.text,
            )
    }
}
