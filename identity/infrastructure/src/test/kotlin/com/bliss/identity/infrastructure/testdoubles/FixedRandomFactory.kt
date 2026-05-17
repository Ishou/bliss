package com.bliss.identity.infrastructure.testdoubles

import com.bliss.identity.application.ports.RandomFactory
import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.auth.State

// Mirrors com.bliss.identity.application.testdoubles.FixedRandomFactory. See FixedClock for the rationale.
class FixedRandomFactory(
    states: List<State> = emptyList(),
    pkceVerifiers: List<PkceVerifier> = emptyList(),
) : RandomFactory {
    private val stateQueue = ArrayDeque(states)
    private val pkceQueue = ArrayDeque(pkceVerifiers)

    override fun newState(): State = stateQueue.removeFirstOrNull() ?: error("FixedRandomFactory exhausted for State.")

    override fun newPkceVerifier(): PkceVerifier = pkceQueue.removeFirstOrNull() ?: error("FixedRandomFactory exhausted for PkceVerifier.")
}
