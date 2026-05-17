package com.bliss.identity.infrastructure.persistence

import com.bliss.identity.application.ports.UserProviderRepository
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.provider.Subject
import com.bliss.identity.domain.provider.UserProvider
import com.bliss.identity.domain.user.UserId
import java.util.concurrent.ConcurrentHashMap

class InMemoryUserProviderRepository : UserProviderRepository {
    private data class Key(
        val provider: Provider,
        val subject: Subject,
    )

    private val byProviderSubject = ConcurrentHashMap<Key, UserProvider>()

    override suspend fun link(userProvider: UserProvider) {
        check(byProviderSubject.values.none { it.userId == userProvider.userId && it.provider == userProvider.provider }) {
            "Duplicate link for (${userProvider.userId}, ${userProvider.provider})"
        }
        check(byProviderSubject[Key(userProvider.provider, userProvider.subject)] == null) {
            "Subject ${userProvider.subject.value} already linked to a different account"
        }
        byProviderSubject[Key(userProvider.provider, userProvider.subject)] = userProvider
    }

    override suspend fun findByProviderAndSubject(
        provider: Provider,
        subject: Subject,
    ): UserProvider? = byProviderSubject[Key(provider, subject)]

    override suspend fun listForUser(userId: UserId): List<UserProvider> = byProviderSubject.values.filter { it.userId == userId }

    override suspend fun deleteForUser(userId: UserId) {
        byProviderSubject.values
            .filter { it.userId == userId }
            .forEach { byProviderSubject.remove(Key(it.provider, it.subject)) }
    }
}
