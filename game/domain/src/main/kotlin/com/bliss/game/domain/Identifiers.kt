package com.bliss.game.domain

import java.security.SecureRandom

private val LOBBY_ID_REGEX = Regex("^[1-9A-HJ-NP-Za-km-z]{8}$")
private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
private const val LOBBY_ID_LENGTH = 8
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

@JvmInline
value class SessionId(
    val value: String,
) {
    init {
        require(SESSION_ID_REGEX.matches(value)) {
            "SessionId must be a UUID v7 string, was '$value'"
        }
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
