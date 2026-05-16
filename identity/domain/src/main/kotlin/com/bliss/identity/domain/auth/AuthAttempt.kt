package com.bliss.identity.domain.auth

import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.user.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class AuthAttemptId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

data class AuthAttempt(
    val id: AuthAttemptId,
    val state: State,
    val pkceVerifier: PkceVerifier,
    val provider: Provider,
    val returnTo: String,
    val linkToUserId: UserId?, // non-null when initiated from a signed-in session
    val expiresAt: Instant,
) {
    fun isExpired(now: Instant): Boolean = !now.isBefore(expiresAt)

    val isLinkingMode: Boolean
        get() = linkToUserId != null
}
