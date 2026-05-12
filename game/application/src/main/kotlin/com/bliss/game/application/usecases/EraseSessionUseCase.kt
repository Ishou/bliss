package com.bliss.game.application.usecases

import com.bliss.game.application.ports.EraseSessionResult
import com.bliss.game.application.ports.LobbyRepository
import com.bliss.game.domain.SessionId

/**
 * Thin wrapper over [LobbyRepository.eraseSession] for the three-rule
 * cascade documented in ADR-0039:
 *
 *  1. Owner + sole player    → delete the lobby.
 *  2. Owner + others present → transfer ownership to the earliest-joined
 *                              remaining player, drop the erased user, and
 *                              anonymise their entries.
 *  3. Non-owner              → drop the playership and anonymise entries.
 *
 * Idempotent: a session id with no server-side data returns all-zero counts.
 */
class EraseSessionUseCase(
    private val repo: LobbyRepository,
) {
    suspend operator fun invoke(sessionId: SessionId): EraseSessionResult = repo.eraseSession(sessionId)
}
