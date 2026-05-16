package com.bliss.identity.application.ports

import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.auth.State

/**
 * Produces fresh CSRF `State` and OAuth `PkceVerifier` values. Production
 * binding (`SecureRandomFactory`, wrapping `java.security.SecureRandom`)
 * lands in Phase 3. Tests use `FixedRandomFactory` to make handshakes
 * deterministic.
 */
interface RandomFactory {
    fun newState(): State

    fun newPkceVerifier(): PkceVerifier
}
