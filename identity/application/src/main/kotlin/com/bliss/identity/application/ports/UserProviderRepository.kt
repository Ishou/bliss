package com.bliss.identity.application.ports

import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.provider.Subject
import com.bliss.identity.domain.provider.UserProvider
import com.bliss.identity.domain.user.UserId

interface UserProviderRepository {
    /**
     * Records a new provider link for the given user.
     * Callers must ensure two preconditions are met:
     * - No existing row for (userId, provider) exists — the primary key; and
     * - No existing row for (provider, subject) exists — an IdP subject maps to
     *   exactly one Bliss account.
     * Violating either precondition is undefined behaviour; implementations may throw.
     */
    suspend fun link(userProvider: UserProvider)

    suspend fun findByProviderAndSubject(
        provider: Provider,
        subject: Subject,
    ): UserProvider?

    suspend fun listForUser(userId: UserId): List<UserProvider>

    suspend fun deleteForUser(userId: UserId)
}
