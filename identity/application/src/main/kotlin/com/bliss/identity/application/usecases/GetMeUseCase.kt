package com.bliss.identity.application.usecases

import com.bliss.identity.application.ports.UserProviderRepository
import com.bliss.identity.application.ports.UserRepository
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import java.time.Instant

data class GetMeQuery(
    val userId: UserId,
)

data class LinkedProviderView(
    val provider: Provider,
    val linkedAt: Instant,
    val emailOptIn: Boolean,
)

data class GetMeResult(
    val userId: UserId,
    val displayName: DisplayName,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val linkedProviders: List<LinkedProviderView>,
)

sealed class GetMeError(message: String) : RuntimeException(message) {
    class UserNotFound : GetMeError("User does not exist.")
}

class GetMeUseCase(
    private val users: UserRepository,
    private val userProviders: UserProviderRepository,
) {
    suspend fun execute(query: GetMeQuery): GetMeResult {
        val user = users.findById(query.userId) ?: throw GetMeError.UserNotFound()
        val providers =
            userProviders.listForUser(query.userId).map { up ->
                LinkedProviderView(
                    provider = up.provider,
                    linkedAt = up.linkedAt,
                    emailOptIn = up.emailAtLink != null,
                )
            }
        return user.toResult(providers)
    }

    private fun User.toResult(linkedProviders: List<LinkedProviderView>): GetMeResult =
        GetMeResult(
            userId = id,
            displayName = displayName,
            createdAt = createdAt,
            lastSeenAt = lastSeenAt,
            linkedProviders = linkedProviders,
        )
}
