package com.bliss.identity.domain.session

import com.bliss.identity.domain.user.UserId
import java.time.Instant

data class Session(
    val id: SessionId,
    val userId: UserId,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val revokedAt: Instant?,
) {
    val isActive: Boolean
        get() = revokedAt == null
}
