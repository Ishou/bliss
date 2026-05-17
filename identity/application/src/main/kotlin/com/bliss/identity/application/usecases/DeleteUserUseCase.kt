package com.bliss.identity.application.usecases

import com.bliss.identity.application.ports.Clock
import com.bliss.identity.application.ports.UserDeletedBroadcaster
import com.bliss.identity.application.ports.UserRepository
import com.bliss.identity.domain.user.UserId
import kotlin.coroutines.cancellation.CancellationException

data class DeleteUserCommand(
    val userId: UserId,
)

sealed class DeleteUserError(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class UserNotFound : DeleteUserError("User does not exist.")

    class BroadcastFailed(
        cause: Throwable,
    ) : DeleteUserError("UserDeleted broadcast failed: ${cause.message}", cause)
}

class DeleteUserUseCase(
    private val users: UserRepository,
    private val broadcaster: UserDeletedBroadcaster,
    private val clock: Clock,
) {
    suspend fun execute(command: DeleteUserCommand) {
        users.findById(command.userId) ?: throw DeleteUserError.UserNotFound()
        val now = clock.now()
        try {
            broadcaster.broadcast(command.userId, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DeleteUserError.BroadcastFailed(e)
        }
        users.delete(command.userId)
    }
}
