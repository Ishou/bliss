package com.bliss.grid.application.analytics

/**
 * Analytics events emitted by `grid/` use cases on user-meaningful state transitions.
 * The infrastructure adapter (`MatomoAnalyticsAdapter`) maps these to wire calls;
 * the application layer is unaware of the analytics backend.
 *
 * Naming follows `docs/analytics/event-taxonomy.md` (`grid:<event>:v<n>`).
 *
 * Properties never include `sessionId`, IP, or any direct identifier — visitor
 * identity is carried by the daily-rotated hash that the adapter computes from
 * a separately-passed identifier (ADR-0025 §3).
 */
sealed interface AnalyticsEvent {
    /** A puzzle was generated and persisted by `LoadOrGeneratePuzzleUseCase`. */
    data class PuzzleGenerated(
        val gridSize: String,
        val language: String,
    ) : AnalyticsEvent

    /** `RequestWordHintUseCase` returned a hint letter (the cap had budget). */
    data class HintUsed(
        val gridSize: String,
        val hintsUsedSoFar: Int,
    ) : AnalyticsEvent
}
