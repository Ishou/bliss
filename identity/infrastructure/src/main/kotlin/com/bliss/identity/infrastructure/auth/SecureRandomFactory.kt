package com.bliss.identity.infrastructure.auth

import com.bliss.identity.application.ports.RandomFactory
import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.auth.State
import java.security.SecureRandom

// Not getInstanceStrong(): blocks /dev/random on Linux; default SecureRandom is OWASP-compliant for PKCE.
class SecureRandomFactory : RandomFactory {
    private val random = SecureRandom()

    override fun newState(): State = State.generate(random)

    override fun newPkceVerifier(): PkceVerifier = PkceVerifier.generate(random)
}
