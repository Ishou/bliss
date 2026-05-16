package com.bliss.identity.application.ports

import com.bliss.identity.domain.auth.AuthAttemptId
import com.bliss.identity.domain.session.SessionId
import com.bliss.identity.domain.user.UserId

/**
 * Generates UUIDv7 identifiers. Production binding (using the
 * `com.fasterxml.uuid:java-uuid-generator` library) lands in Phase 3.
 * Tests use `FixedIdGenerator` to make IDs deterministic.
 */
interface IdGenerator {
    fun newUserId(): UserId

    fun newSessionId(): SessionId

    fun newAuthAttemptId(): AuthAttemptId
}
