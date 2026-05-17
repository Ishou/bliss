package com.bliss.identity.infrastructure.id

import com.bliss.identity.application.ports.IdGenerator
import com.bliss.identity.domain.auth.AuthAttemptId
import com.bliss.identity.domain.session.SessionId
import com.bliss.identity.domain.user.UserId
import com.fasterxml.uuid.Generators

// Private generator reused for monotonic clock-seq; thread-safe per java-uuid-generator contract.
class UuidV7IdGenerator : IdGenerator {
    private val generator = Generators.timeBasedEpochGenerator()

    override fun newUserId(): UserId = UserId(generator.generate())

    override fun newSessionId(): SessionId = SessionId(generator.generate())

    override fun newAuthAttemptId(): AuthAttemptId = AuthAttemptId(generator.generate())
}
