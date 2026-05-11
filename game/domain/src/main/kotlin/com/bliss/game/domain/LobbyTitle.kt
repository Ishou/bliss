package com.bliss.game.domain

/**
 * Optional human-friendly lobby title surfaced on the Accueil "Mes parties"
 * card and on a lobby's share page. Distinct from [LobbyCode] (a join
 * secret) and [LobbyId] (an opaque key) — the title is purely display.
 * The Lobby aggregate may carry a null title; this class only validates a
 * present value. Mirrors the `title` schema in `game/api/openapi.yaml`;
 * keep the bounds in sync.
 */
@JvmInline
value class LobbyTitle(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "LobbyTitle must not be blank" }
        require(value.length in 1..MAX_LENGTH) {
            "LobbyTitle length must be 1..$MAX_LENGTH, was ${value.length}"
        }
        require(value.none { it.isISOControl() }) {
            "LobbyTitle must not contain control characters"
        }
    }

    companion object {
        const val MAX_LENGTH = 64
    }
}
