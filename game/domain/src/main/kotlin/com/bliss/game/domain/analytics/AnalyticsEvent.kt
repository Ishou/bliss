package com.bliss.game.domain.analytics

/**
 * Analytics events emitted by `game/` use cases on user-meaningful state transitions.
 * The infrastructure adapter (`MatomoAnalyticsAdapter`) maps these to wire calls;
 * the application layer is unaware of the analytics backend.
 *
 * Naming follows `docs/analytics/event-taxonomy.md`: every event maps to a
 * versioned wire name like `game:lobby_created:v1`. Bumps create a new sealed
 * subtype and the old one is kept until consumers migrate.
 *
 * Properties are intentionally minimal — never include `sessionId`, IP, pseudonym,
 * or any direct identifier. Visitor identity is carried by the rotated hash that
 * the adapter computes from a separately-passed `SessionId` (ADR-0025 §3).
 */
sealed interface AnalyticsEvent {
    /** Owner clicked "create lobby" and the use case persisted a fresh `WAITING` lobby. */
    data class LobbyCreated(
        val gridSize: String,
    ) : AnalyticsEvent

    /** A non-owner joined an existing lobby; `playerCount` is post-join. */
    data class LobbyJoined(
        val playerCount: Int,
    ) : AnalyticsEvent

    /** Owner clicked "start"; all players synced to `IN_PROGRESS`. */
    data class GameStarted(
        val gridSize: String,
        val playerCount: Int,
    ) : AnalyticsEvent

    /** All cells filled correctly; lobby is `COMPLETED`. */
    data class GameSolved(
        val gridSize: String,
        val playerCount: Int,
        val durationMs: Long,
    ) : AnalyticsEvent

    /** A player ran the rename use case (multiplayer display name change). */
    data object PlayerRenamed : AnalyticsEvent

    /** A player ran the leave use case (graceful exit, not a disconnect). */
    data object LobbyLeft : AnalyticsEvent
}
