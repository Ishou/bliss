package com.bliss.game.domain

import java.security.SecureRandom

private val LOBBY_ID_REGEX = Regex("^[1-9A-HJ-NP-Za-km-z]{8}$")
private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
private const val LOBBY_ID_LENGTH = 8

// Crockford-style alphabet: A-H, J, K, M, N, P-Z, 2-9. Excludes the
// ambiguous chars 0/O, 1/I/L (I and L look like 1; L can also look like
// 1; 0 looks like O). The naive `J-N` range includes L — broken out
// into `J-K` and `M-N` to drop it.
private val LOBBY_CODE_REGEX = Regex("^[A-HJKM-NP-Z2-9]{6}$")
private const val LOBBY_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
private const val LOBBY_CODE_LENGTH = 6
private val SESSION_ID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

@JvmInline
value class LobbyId(
    val value: String,
) {
    init {
        require(LOBBY_ID_REGEX.matches(value)) {
            "LobbyId must match base58 pattern ${LOBBY_ID_REGEX.pattern}, was '$value'"
        }
    }

    companion object {
        private val secureRandom = SecureRandom()

        fun generate(): LobbyId {
            val chars = CharArray(LOBBY_ID_LENGTH)
            for (i in 0 until LOBBY_ID_LENGTH) {
                chars[i] = BASE58_ALPHABET[secureRandom.nextInt(BASE58_ALPHABET.length)]
            }
            return LobbyId(String(chars))
        }
    }
}

/**
 * Human-friendly six-character lobby join code surfaced to non-owner
 * players (the Accueil "Rejoindre avec un code" input). Crockford-style
 * alphabet — upper-case letters + digits with `0`/`O`, `1`/`I`/`L` and
 * the digit `1` removed so a player reading the code aloud cannot land
 * on a different lobby. Mirrors the `code` schema in
 * `game/api/openapi.yaml`; keep the regex / alphabet in sync.
 */
@JvmInline
value class LobbyCode(
    val value: String,
) {
    init {
        require(LOBBY_CODE_REGEX.matches(value)) {
            "LobbyCode must match pattern ${LOBBY_CODE_REGEX.pattern}, was '$value'"
        }
    }

    companion object {
        private val secureRandom = SecureRandom()

        fun generate(): LobbyCode {
            val chars = CharArray(LOBBY_CODE_LENGTH)
            for (i in 0 until LOBBY_CODE_LENGTH) {
                chars[i] = LOBBY_CODE_ALPHABET[secureRandom.nextInt(LOBBY_CODE_ALPHABET.length)]
            }
            return LobbyCode(String(chars))
        }
    }
}

@JvmInline
value class SessionId(
    val value: String,
) {
    init {
        require(SESSION_ID_REGEX.matches(value)) {
            "SessionId must be a UUID v7 string, was '$value'"
        }
    }

    companion object {
        /**
         * RGPD-anonymisation sentinel for cell entries authored by an erased
         * user (ADR-0039). The `7` nibble at position 13 satisfies the v7
         * version check; the `8` nibble at position 17 is the RFC 4122
         * variant marker, so this value clears the SESSION_ID_REGEX init
         * check while never colliding with a real generated session.
         */
        val ANON: SessionId = SessionId("00000000-0000-7000-8000-000000000000")
    }
}

@JvmInline
value class Pseudonym(
    val value: String,
) {
    init {
        require(value == value.trim()) {
            "Pseudonym must not have leading or trailing whitespace, was '$value'"
        }
        require(value.isNotEmpty()) { "Pseudonym must not be empty" }
        require(value.length <= MAX_LENGTH) {
            "Pseudonym must be at most $MAX_LENGTH chars, was ${value.length}"
        }
    }

    companion object {
        const val MAX_LENGTH = 32

        fun of(raw: String): Pseudonym = Pseudonym(raw.trim())
    }
}

@JvmInline
value class Letter(
    val value: Char,
) {
    init {
        require(value in 'A'..'Z') { "Letter must be uppercase A-Z, was '$value'" }
    }
}
