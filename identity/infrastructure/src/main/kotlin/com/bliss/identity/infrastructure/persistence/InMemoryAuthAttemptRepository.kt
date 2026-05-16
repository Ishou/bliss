package com.bliss.identity.infrastructure.persistence

import com.bliss.identity.application.ports.AuthAttemptRepository
import com.bliss.identity.domain.auth.AuthAttempt
import com.bliss.identity.domain.auth.State
import java.util.concurrent.ConcurrentHashMap

class InMemoryAuthAttemptRepository : AuthAttemptRepository {
    private val byState = ConcurrentHashMap<State, AuthAttempt>()

    override suspend fun create(attempt: AuthAttempt) {
        byState[attempt.state] = attempt
    }

    override suspend fun findByState(state: State): AuthAttempt? = byState[state]

    override suspend fun deleteByState(state: State) {
        byState.remove(state)
    }
}
