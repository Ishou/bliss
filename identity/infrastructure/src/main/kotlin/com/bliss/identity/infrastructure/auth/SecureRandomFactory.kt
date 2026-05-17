package com.bliss.identity.infrastructure.auth

import com.bliss.identity.application.ports.RandomFactory
import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.auth.State
import java.security.SecureRandom

/**
 * Production binding of [RandomFactory] — backed by a single shared
 * [SecureRandom] instance (thread-safe; the JVM's default
 * `SecureRandom.getInstanceStrong()` is intentionally not used because it can
 * block on `/dev/random` on Linux servers, and the non-strong default is
 * already cryptographically suitable per OWASP's PKCE guidance).
 */
class SecureRandomFactory : RandomFactory {
    private val random = SecureRandom()

    override fun newState(): State = State.generate(random)

    override fun newPkceVerifier(): PkceVerifier = PkceVerifier.generate(random)
}
