package com.bliss.identity.application.ports

import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.provider.Subject
import com.bliss.identity.domain.provider.UserProvider
import com.bliss.identity.domain.user.UserId

interface UserProviderRepository {
    suspend fun link(userProvider: UserProvider)

    suspend fun findByProviderAndSubject(provider: Provider, subject: Subject): UserProvider?

    suspend fun listForUser(userId: UserId): List<UserProvider>

    suspend fun deleteForUser(userId: UserId)
}
