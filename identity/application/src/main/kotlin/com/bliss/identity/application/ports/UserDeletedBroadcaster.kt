package com.bliss.identity.application.ports

import com.bliss.identity.domain.user.UserId
import java.time.Instant

/** Must be invoked before the local hard-delete; a failure here leaves the user record intact and the caller must retry. */
fun interface UserDeletedBroadcaster {
    suspend fun broadcast(
        userId: UserId,
        deletedAt: Instant,
    )
}
