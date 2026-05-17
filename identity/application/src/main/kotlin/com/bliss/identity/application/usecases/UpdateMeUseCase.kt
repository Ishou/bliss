package com.bliss.identity.application.usecases

import com.bliss.identity.application.ports.UserRepository
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.UserId

data class UpdateMeCommand(
    val userId: UserId,
    val displayName: String?,
    /** Intentionally a no-op in v1 — storage model + OIDC re-prompt design deferred. */
    val emailOptIn: Boolean? = null,
)

sealed class UpdateMeError(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class UserNotFound : UpdateMeError("User does not exist.")

    class InvalidDisplayName(
        cause: Throwable,
    ) : UpdateMeError("Invalid display name: ${cause.message}", cause)
}

class UpdateMeUseCase(
    private val users: UserRepository,
) {
    suspend fun execute(command: UpdateMeCommand) {
        users.findById(command.userId) ?: throw UpdateMeError.UserNotFound()

        command.displayName?.let { raw ->
            val name =
                runCatching { DisplayName.of(raw) }
                    .getOrElse { e -> throw UpdateMeError.InvalidDisplayName(e) }
            users.updateDisplayName(command.userId, name)
        }
    }
}
