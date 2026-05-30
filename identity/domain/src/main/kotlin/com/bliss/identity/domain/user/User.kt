package com.bliss.identity.domain.user

import java.time.Instant

data class User(
    val id: UserId,
    val displayName: DisplayName,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val role: Role = Role.PLAYER,
)
