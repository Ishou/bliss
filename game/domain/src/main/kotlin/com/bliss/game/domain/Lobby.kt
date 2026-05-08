package com.bliss.game.domain

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

enum class LobbyLifecycleState {
    WAITING,
    IN_PROGRESS,
    COMPLETED,
}

data class Player(
    val sessionId: SessionId,
    val pseudonym: Pseudonym,
    val joinedAt: Instant,
)

/**
 * A single placed letter in the canonical entries map. Conflicts are resolved
 * last-write-wins per ADR-0018 (§"Conflict policy"); only [writtenAt] decides
 * ordering, [sessionId] is for attribution / UI tinting only.
 */
data class CellEntry(
    val sessionId: SessionId,
    val letter: Letter,
    val writtenAt: Instant,
)

/**
 * Live game state embedded in a [Lobby] once the owner has clicked Start.
 * [entries] is the authoritative server-side cell map; [completedAt] is null
 * until the puzzle is solved. [lockedPositions] is the cumulative set of
 * cells whose containing word was validated correct — server-enforced
 * read-only and surfaced on the lobbyState snapshot for late-joiners.
 */
data class GameSession(
    val puzzle: GamePuzzle,
    val entries: Map<Position, CellEntry>,
    val startedAt: Instant,
    val completedAt: Instant?,
    val lockedPositions: Set<Position> = emptySet(),
) {
    init {
        if (completedAt != null) {
            require(!completedAt.isBefore(startedAt)) {
                "GameSession completedAt ($completedAt) must not be before startedAt ($startedAt)"
            }
        }
    }

    fun isSolved(): Boolean {
        val answerable = letterCellPositionsWithAnswer()
        // A puzzle with no answerable cells is not solvable, so it is not solved.
        return answerable.isNotEmpty() && solvedPositions() == answerable
    }

    /** Positions whose [LetterCell] answer matches the placed [CellEntry]. */
    fun solvedPositions(): Set<Position> =
        puzzle.cells
            .asSequence()
            .filterIsInstance<LetterCell>()
            .filter { it.answer != null && entries[it.position]?.letter == it.answer }
            .map { it.position }
            .toSet()

    private fun letterCellPositionsWithAnswer(): Set<Position> =
        puzzle.cells
            .asSequence()
            .filterIsInstance<LetterCell>()
            .filter { it.answer != null }
            .map { it.position }
            .toSet()

    /** Wall-clock duration. Frozen at [completedAt] once solved, else live. */
    fun duration(now: Instant): Duration {
        val end = completedAt ?: now
        return java.time.Duration
            .between(startedAt, end)
            .toKotlinDuration()
    }
}

/**
 * In-memory lobby aggregate. State machine: WAITING → IN_PROGRESS → COMPLETED.
 * `game` is null while WAITING and required otherwise (validated in [init]).
 *
 * [lastActivityAt] is bumped on every state-changing use case (create, join,
 * rename, setGridConfig, startGame, updateCell, leave). It is consumed by the
 * lobby garbage collector to evict abandoned WAITING lobbies and is purely
 * server-side bookkeeping — not on the wire (see `game/api/openapi.yaml`).
 */
data class Lobby(
    val id: LobbyId,
    val ownerSessionId: SessionId,
    val players: Map<SessionId, Player>,
    val state: LobbyLifecycleState,
    val gridConfig: GridConfig,
    val game: GameSession?,
    val lastActivityAt: Instant,
) {
    init {
        require(players.size <= MAX_PLAYERS) {
            "Lobby may hold at most $MAX_PLAYERS players, was ${players.size}"
        }
        require(players.containsKey(ownerSessionId)) {
            "Lobby owner $ownerSessionId must be a member of the lobby"
        }
        when (state) {
            LobbyLifecycleState.WAITING ->
                require(game == null) { "Lobby in WAITING must not carry a GameSession" }
            LobbyLifecycleState.IN_PROGRESS, LobbyLifecycleState.COMPLETED ->
                require(game != null) { "Lobby in $state must carry a GameSession" }
        }
        if (state == LobbyLifecycleState.COMPLETED) {
            require(game!!.completedAt != null) {
                "Lobby in COMPLETED must have a completedAt timestamp on its GameSession"
            }
        }
    }

    fun isOwner(sessionId: SessionId): Boolean = ownerSessionId == sessionId

    fun isFull(): Boolean = players.size >= MAX_PLAYERS

    fun hasJoined(sessionId: SessionId): Boolean = players.containsKey(sessionId)

    /** Returns a copy with [lastActivityAt] advanced to [now]. */
    fun touched(now: Instant): Lobby = copy(lastActivityAt = now)

    companion object {
        const val MAX_PLAYERS = 8
    }
}
