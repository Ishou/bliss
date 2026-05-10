package com.bliss.grid.application.puzzle

import com.bliss.grid.application.analytics.AnalyticsEventSink
import com.bliss.grid.domain.analytics.AnalyticsEvent
import com.bliss.grid.domain.generation.ClueCooldownPolicy
import com.bliss.grid.domain.generation.ClueCooldownRepository
import com.bliss.grid.domain.generation.ClueId
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

/**
 * Composes [PuzzleRepository] with the existing [GeneratePuzzleUseCase] so
 * GET `/v1/puzzles/{id}` becomes idempotent: the first call generates +
 * stores; subsequent calls (validate, hints, refresh) return the same grid.
 *
 * Decoupling the wire id from the generator implementation matters for
 * forward-compat: any future change to corpus / CSP heuristics / Random
 * stdlib would silently invalidate existing ids if we re-derived from a
 * seed. Storing the placement list keeps live puzzles stable across
 * generator code churn.
 */
class LoadOrGeneratePuzzleUseCase(
    private val puzzleRepository: PuzzleRepository,
    private val generatePuzzle: GeneratePuzzleUseCase,
    private val clock: Clock = Clock.systemUTC(),
    private val hintsAllowed: Int = DEFAULT_HINTS_ALLOWED,
    private val title: String = DEFAULT_TITLE,
    private val language: String = DEFAULT_LANGUAGE,
    private val analyticsEventSink: AnalyticsEventSink = AnalyticsEventSink.Noop,
    private val cooldownRepository: ClueCooldownRepository? = null,
    private val cooldownMax: Int = DEFAULT_COOLDOWN_MAX,
) {
    private val log = LoggerFactory.getLogger(LoadOrGeneratePuzzleUseCase::class.java)

    fun execute(
        puzzleId: UUID,
        width: Int? = null,
        height: Int? = null,
        sessionId: UUID? = null,
    ): StoredPuzzle? =
        puzzleRepository.getOrCompute(puzzleId) {
            val cooldown = cooldownRepository
            val cooldownActive = cooldown != null && sessionId != null
            val onCooldownBefore =
                if (cooldownActive) cooldown!!.snapshot(sessionId!!).onCooldown else emptySet()
            val policy =
                if (cooldownActive) ClueCooldownPolicy.fromSet(onCooldownBefore) else ClueCooldownPolicy.Inert
            val grid =
                generatePuzzle.execute(
                    width = width,
                    height = height,
                    cooldownPolicy = policy,
                ) ?: return@getOrCompute null

            if (cooldownActive) {
                val usedClues = grid.placements.map { ClueId(it.word.text, it.chosenClue.text) }
                val newSeq =
                    cooldown!!.recordGeneration(
                        bucketId = sessionId!!,
                        usedClues = usedClues,
                        rollMaxInclusive = cooldownMax,
                    )
                // event=clue_cooldown_recorded — observable via SigNoz log queries.
                // fallback_count counts placements whose chosen clue was on
                // cooldown coming in — i.e. the picker had no fresh alternate
                // and fell back to the existing uniform-random branch.
                val fallbackCount = usedClues.count { it in onCooldownBefore }
                log.info(
                    "event=clue_cooldown_recorded bucket_id={} clue_count={} fallback_count={} new_seq={} roll_max={}",
                    sessionId,
                    usedClues.size,
                    fallbackCount,
                    newSeq,
                    cooldownMax,
                )
            }

            analyticsEventSink.record(
                AnalyticsEvent.PuzzleGenerated(
                    gridSize = "${grid.width}x${grid.height}",
                    language = language,
                ),
                sessionId = null,
            )
            StoredPuzzle(
                grid = grid,
                title = title,
                language = language,
                hintsAllowed = hintsAllowed,
                createdAt = clock.instant(),
            )
        }

    companion object {
        const val DEFAULT_HINTS_ALLOWED: Int = 3
        const val DEFAULT_TITLE: String = "Grille du jour"
        const val DEFAULT_LANGUAGE: String = "fr"

        /** Max `rand(1..X)` cooldown TTL per ADR-0031; env-backed via `cooldownMax` ctor param in `Module.kt`. */
        const val DEFAULT_COOLDOWN_MAX: Int = 8
    }
}
