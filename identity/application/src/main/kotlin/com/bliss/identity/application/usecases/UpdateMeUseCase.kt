package com.bliss.identity.application.usecases

import com.bliss.identity.application.ports.Clock
import com.bliss.identity.application.ports.UserRenamedBroadcaster
import com.bliss.identity.application.ports.UserRepository
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.UserId
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

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
    private val broadcaster: UserRenamedBroadcaster,
    private val clock: Clock,
) {
    suspend fun execute(command: UpdateMeCommand) {
        val current = users.findById(command.userId) ?: throw UpdateMeError.UserNotFound()

        val raw = command.displayName ?: return
        val name =
            runCatching { DisplayName.of(raw) }
                .getOrElse { e -> throw UpdateMeError.InvalidDisplayName(e) }
        if (name == current.displayName) return
        users.updateDisplayName(command.userId, name)
        try {
            broadcaster.broadcast(command.userId, name, clock.now())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Fire-and-forget per ADR-0049. Local rename already succeeded;
            // transport failure is logged and swallowed.
            log.warn("user.renamed broadcast failed for {}", command.userId, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(UpdateMeUseCase::class.java)
    }
}
