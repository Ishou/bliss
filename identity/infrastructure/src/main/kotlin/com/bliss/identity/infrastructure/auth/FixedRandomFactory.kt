package com.bliss.identity.infrastructure.auth

import com.bliss.identity.application.ports.RandomFactory
import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.auth.State

/**
 * Test double — returns the values in the configured sequence. Production
 * binding (`SecureRandomFactory`, wrapping `java.security.SecureRandom`)
 * lands in Phase 3.
 */
class FixedRandomFactory(
    states: List<State> = emptyList(),
    pkceVerifiers: List<PkceVerifier> = emptyList(),
) : RandomFactory {
    private val stateQueue = ArrayDeque(states)
    private val pkceQueue = ArrayDeque(pkceVerifiers)

    override fun newState(): State = stateQueue.removeFirstOrNull() ?: error("FixedRandomFactory exhausted for State.")

    override fun newPkceVerifier(): PkceVerifier = pkceQueue.removeFirstOrNull() ?: error("FixedRandomFactory exhausted for PkceVerifier.")
}
