package com.bliss.identity.infrastructure.id

import com.bliss.identity.application.ports.IdGenerator
import com.bliss.identity.domain.auth.AuthAttemptId
import com.bliss.identity.domain.session.SessionId
import com.bliss.identity.domain.user.UserId
import com.fasterxml.uuid.Generators

/**
 * Production binding of [IdGenerator] — uses `com.fasterxml.uuid:java-uuid-generator`
 * UUIDv7 generator (time-ordered, RFC 9562). Same generator instance is reused per
 * call site for monotonic clock-seq behaviour.
 */
class UuidV7IdGenerator : IdGenerator {
    private val generator = Generators.timeBasedEpochGenerator()

    override fun newUserId(): UserId = UserId(generator.generate())

    override fun newSessionId(): SessionId = SessionId(generator.generate())

    override fun newAuthAttemptId(): AuthAttemptId = AuthAttemptId(generator.generate())
}
