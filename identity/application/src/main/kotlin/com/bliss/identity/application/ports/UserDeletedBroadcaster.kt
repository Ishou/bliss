package com.bliss.identity.application.ports

import com.bliss.identity.domain.user.UserId
import java.time.Instant

/**
 * Notifies downstream contexts that a user has been hard-deleted so they can wipe their
 * user-scoped rows. Invoked BEFORE the local hard-delete; downstream wipes happen first so a
 * failure here leaves the user record intact. Production binding (HTTP fan-out) lands in Phase 6;
 * the in-memory binding captures events for tests.
 */
fun interface UserDeletedBroadcaster {
    suspend fun broadcast(
        userId: UserId,
        deletedAt: Instant,
    )
}
