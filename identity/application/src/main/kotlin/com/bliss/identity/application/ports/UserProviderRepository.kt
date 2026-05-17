package com.bliss.identity.application.ports

import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.provider.Subject
import com.bliss.identity.domain.provider.UserProvider
import com.bliss.identity.domain.user.UserId

interface UserProviderRepository {
    /**
     * Records a new provider link for the given user.
     * Callers must ensure no existing row for (userId, provider) exists;
     * a second call for the same pair is undefined behaviour and implementations
     * may throw.
     */
    suspend fun link(userProvider: UserProvider)

    suspend fun findByProviderAndSubject(
        provider: Provider,
        subject: Subject,
    ): UserProvider?

    suspend fun listForUser(userId: UserId): List<UserProvider>

    suspend fun deleteForUser(userId: UserId)
}
