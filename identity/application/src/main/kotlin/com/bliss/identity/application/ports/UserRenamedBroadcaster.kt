package com.bliss.identity.application.ports

import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.UserId
import java.time.Instant

/** Fire-and-forget notification of a display-name change (ADR-0049). */
fun interface UserRenamedBroadcaster {
    suspend fun broadcast(
        userId: UserId,
        newDisplayName: DisplayName,
        renamedAt: Instant,
    )
}
