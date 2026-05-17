package com.bliss.identity.application.ports

import java.time.Instant

/** Signs a client-assertion JWT for token-endpoint authentication (e.g. Apple's `private_key_jwt`). */
fun interface ClientAssertionSigner {
    fun sign(now: Instant): String
}
