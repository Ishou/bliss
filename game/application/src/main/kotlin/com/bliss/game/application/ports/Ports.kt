package com.bliss.game.application.ports

import com.bliss.game.domain.GamePuzzle
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyId
import java.time.Instant

/**
 * Atomic lobby state operations. The infrastructure adapter (Wave D) holds a
 * per-lobby [java.util.concurrent.locks.ReentrantLock] under [mutate] so use
 * cases can call it without per-call locking concerns.
 */
interface LobbyRepository {
    suspend fun findById(id: LobbyId): Lobby?

    suspend fun save(lobby: Lobby): Lobby

    /**
     * Read-modify-write under a per-lobby lock. Returns the new state, or `null`
     * when no lobby exists for [id]. The mutator is invoked at most once.
     */
    suspend fun mutate(
        id: LobbyId,
        mutator: (Lobby) -> Lobby,
    ): Lobby?

    suspend fun delete(id: LobbyId)
}

/**
 * Fetches a fresh [GamePuzzle] for the lobby owner's chosen dimensions. The
 * HTTP adapter (Wave D) calls grid/'s `GET /v1/puzzles/{id}?width&height`;
 * this layer is unaware of transport.
 */
interface PuzzleProvider {
    suspend fun fetch(
        width: Int,
        height: Int,
    ): GamePuzzle
}

/** Testable time. `SystemClock` lives in infrastructure (Wave D). */
interface Clock {
    fun now(): Instant
}
