package com.bliss.grid.application.puzzle

import java.sql.Connection
import java.util.UUID

/** Serialises per-user hint writes; [block] runs inside a pg_advisory_xact_lock transaction. */
interface HintWriteCoordinator {
    suspend fun <T> withUserLock(
        userId: UUID,
        block: suspend (Connection) -> T,
    ): T
}
