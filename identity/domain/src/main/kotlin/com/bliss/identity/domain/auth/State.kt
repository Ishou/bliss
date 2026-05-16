package com.bliss.identity.domain.auth

import java.security.SecureRandom
import java.util.Base64

/**
 * Opaque CSRF token tying an OIDC handshake's authorise redirect to its callback.
 * 32 random bytes encoded url-safe base64 without padding -> 43 characters.
 */
@JvmInline
value class State private constructor(
    val value: String,
) {
    companion object {
        private const val BYTE_LENGTH = 32

        fun generate(random: SecureRandom): State {
            val bytes = ByteArray(BYTE_LENGTH)
            random.nextBytes(bytes)
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            return State(encoded)
        }

        fun of(raw: String): State {
            require(raw.length >= 32) { "State token must be at least 32 characters for adequate CSRF entropy." }
            return State(raw)
        }
    }
}
