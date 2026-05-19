package com.bliss.game.application.lobby

import com.bliss.game.domain.UserId
import java.sql.Connection

/** Serialises per-user lobby writes; [block] runs inside a pg_advisory_xact_lock transaction. */
interface LobbyWriteCoordinator {
    suspend fun <T> withUserLock(
        userId: UserId,
        block: suspend (Connection) -> T,
    ): T
}
