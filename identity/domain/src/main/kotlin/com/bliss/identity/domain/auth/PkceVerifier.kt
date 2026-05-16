package com.bliss.identity.domain.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE code verifier (RFC 7636). 32 random bytes encoded url-safe base64 without
 * padding -> 43 ASCII characters, in the unreserved-character set allowed by
 * the RFC. The S256 challenge is SHA-256(verifier) re-encoded the same way.
 */
@JvmInline
value class PkceVerifier private constructor(
    val value: String,
) {
    fun challenge(): String {
        val sha =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha)
    }

    companion object {
        private const val BYTE_LENGTH = 32

        fun generate(random: SecureRandom): PkceVerifier {
            val bytes = ByteArray(BYTE_LENGTH)
            random.nextBytes(bytes)
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            return PkceVerifier(encoded)
        }

        fun of(raw: String): PkceVerifier {
            require(raw.isNotBlank()) { "PKCE verifier must not be blank." }
            return PkceVerifier(raw)
        }
    }
}
