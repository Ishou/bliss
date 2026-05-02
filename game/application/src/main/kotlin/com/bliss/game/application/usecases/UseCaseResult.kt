package com.bliss.game.application.usecases

import com.bliss.game.application.ports.LobbyEvent
import com.bliss.game.domain.Lobby

/**
 * Successful outcome of a use case. [value] is the new lobby snapshot; [events]
 * is the ordered list of [LobbyEvent]s the API layer must broadcast.
 */
data class UseCaseResult<T>(
    val value: T,
    val events: List<LobbyEvent>,
)

/** Failure modes shared across use cases. */
sealed interface UseCaseError {
    data object LobbyNotFound : UseCaseError

    data object LobbyFull : UseCaseError

    data object NotOwner : UseCaseError

    /** Wrong lifecycle for this operation (e.g. `startGame` on a COMPLETED lobby). */
    data object InvalidState : UseCaseError

    data object PlayerNotInLobby : UseCaseError

    data class InvalidArgument(
        val detail: String,
    ) : UseCaseError
}

/** `Either`-style outcome — success carries the result, failure carries the error. */
sealed interface UseCaseOutcome<out T> {
    data class Success<T>(
        val result: UseCaseResult<T>,
    ) : UseCaseOutcome<T>

    data class Failure(
        val error: UseCaseError,
    ) : UseCaseOutcome<Nothing>
}

internal fun <T> success(
    value: T,
    events: List<LobbyEvent>,
): UseCaseOutcome<T> = UseCaseOutcome.Success(UseCaseResult(value, events))

internal fun failure(error: UseCaseError): UseCaseOutcome<Nothing> = UseCaseOutcome.Failure(error)

internal typealias LobbyOutcome = UseCaseOutcome<Lobby>
