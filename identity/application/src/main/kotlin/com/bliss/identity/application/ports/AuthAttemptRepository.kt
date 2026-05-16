package com.bliss.identity.application.ports

import com.bliss.identity.domain.auth.AuthAttempt
import com.bliss.identity.domain.auth.State

interface AuthAttemptRepository {
    suspend fun create(attempt: AuthAttempt)

    suspend fun findByState(state: State): AuthAttempt?

    suspend fun deleteByState(state: State)
}
