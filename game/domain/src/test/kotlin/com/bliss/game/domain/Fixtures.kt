package com.bliss.game.domain

import java.time.Instant
import java.util.UUID

internal object Fixtures {
    val sessionA = SessionId("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b")
    val sessionB = SessionId("0190e3b2-1c45-7d2e-9a3f-c0d1e2f3a4b5")
    val now: Instant = Instant.parse("2026-05-02T15:30:00Z")
    val later: Instant = Instant.parse("2026-05-02T15:33:04.250Z")

    fun player(
        sessionId: SessionId = sessionA,
        pseudonym: String = "Alice",
        joinedAt: Instant = now,
    ): Player = Player(sessionId, Pseudonym(pseudonym), joinedAt)

    /**
     * 5x5 puzzle with two letter cells (`P` at 0,3 and `A` at 0,4) and one
     * block at 0,0. Enough to exercise solved/unsolved branches.
     */
    fun puzzle(): GamePuzzle =
        GamePuzzle(
            id = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c"),
            width = 5,
            height = 5,
            cells =
                listOf(
                    BlockCell(Position(0, 0)),
                    LetterCell(Position(0, 3), Letter('P')),
                    LetterCell(Position(0, 4), Letter('A')),
                ),
            clues = null,
            createdAt = now,
        )

    fun gameSession(
        entries: Map<Position, CellEntry> = emptyMap(),
        startedAt: Instant = now,
        completedAt: Instant? = null,
    ): GameSession = GameSession(puzzle(), entries, startedAt, completedAt)

    fun entry(
        letter: Char,
        writtenAt: Instant = later,
        sessionId: SessionId = sessionA,
    ): CellEntry = CellEntry(sessionId, Letter(letter), writtenAt)

    fun lobby(
        state: LobbyLifecycleState = LobbyLifecycleState.WAITING,
        players: Map<SessionId, Player> = mapOf(sessionA to player()),
        game: GameSession? = null,
        ownerSessionId: SessionId = sessionA,
        gridConfig: GridConfig = GridConfig(7, 7),
    ): Lobby =
        Lobby(
            id = LobbyId("7gQ2xK9p"),
            ownerSessionId = ownerSessionId,
            players = players,
            state = state,
            gridConfig = gridConfig,
            game = game,
        )
}
