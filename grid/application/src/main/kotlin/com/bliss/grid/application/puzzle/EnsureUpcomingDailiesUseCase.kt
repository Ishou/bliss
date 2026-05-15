package com.bliss.grid.application.puzzle

import com.bliss.grid.domain.generation.ClueCooldownPolicy
import com.bliss.grid.domain.generation.ClueCooldownRepository
import com.bliss.grid.domain.generation.ClueId
import com.bliss.grid.domain.model.Grid
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random

/** Pre-generates the rolling 7-day daily-puzzle window. See ADR-0042. */
class EnsureUpcomingDailiesUseCase(
    private val puzzleRepository: PuzzleRepository,
    private val gridGenerationPort: GridGenerationPort,
    private val dailyPuzzleSelector: DailyPuzzleSelector,
    private val cooldownRepository: ClueCooldownRepository? = null,
    private val cooldownMax: Int = LoadOrGeneratePuzzleUseCase.DEFAULT_COOLDOWN_MAX,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val clock: Clock = Clock.systemUTC(),
    private val title: String = LoadOrGeneratePuzzleUseCase.DEFAULT_TITLE,
    private val language: String = LoadOrGeneratePuzzleUseCase.DEFAULT_LANGUAGE,
    private val hintsAllowed: Int = LoadOrGeneratePuzzleUseCase.DEFAULT_HINTS_ALLOWED,
    private val windowDays: Int = DEFAULT_WINDOW_DAYS,
    private val innerAttempts: Int = DEFAULT_INNER_ATTEMPTS,
    private val perAttemptTimeoutMs: Long = DEFAULT_PER_ATTEMPT_TIMEOUT_MS,
) {
    private val log = LoggerFactory.getLogger(EnsureUpcomingDailiesUseCase::class.java)

    fun execute(today: LocalDate): Summary {
        val persistedDates = mutableListOf<LocalDate>()
        val generatedDates = mutableListOf<LocalDate>()
        val failedDates = mutableListOf<LocalDate>()
        val skippedDates = mutableListOf<LocalDate>()

        // Sequential: cooldown snapshot from day N biases day N+1; parallel runs corrupt the ordering.
        // Stop on first failure so day N+1 never persists with a snapshot that ignores day N's clues.
        var stopped = false
        for (offset in 0 until windowDays) {
            val date = today.plusDays(offset.toLong())
            if (stopped) {
                skippedDates += date
                continue
            }
            val puzzleId = dailyPuzzleSelector.puzzleIdForDate(date)
            if (puzzleRepository.get(puzzleId) != null) {
                log.info("daily_already_persisted date={} puzzle_id={}", date, puzzleId)
                persistedDates += date
                continue
            }
            val started = clock.millis()
            val (grid, attempts) = generateForDate(date)
            val elapsedMs = clock.millis() - started
            if (grid == null) {
                log.warn(
                    "daily_generation_exhausted date={} puzzle_id={} attempts={} elapsed_ms={}",
                    date,
                    puzzleId,
                    attempts,
                    elapsedMs,
                )
                failedDates += date
                stopped = true
                continue
            }
            persistGenerated(puzzleId, grid)
            log.info(
                "daily_generated date={} puzzle_id={} attempts={} elapsed_ms={}",
                date,
                puzzleId,
                attempts,
                elapsedMs,
            )
            generatedDates += date
        }
        return Summary(
            persistedDates = persistedDates.toList(),
            generatedDates = generatedDates.toList(),
            failedDates = failedDates.toList(),
            skippedDates = skippedDates.toList(),
        )
    }

    private fun generateForDate(date: LocalDate): Pair<Grid?, Int> {
        val cooldownPolicy = cooldownPolicyFor()
        for (attempt in 0 until maxAttempts) {
            val grid =
                gridGenerationPort.generate(
                    randomSeed = seedFor(date, attempt),
                    cooldownPolicy = cooldownPolicy,
                    attempts = innerAttempts,
                    perAttemptTimeoutMs = perAttemptTimeoutMs,
                )
            if (grid != null) return grid to (attempt + 1)
        }
        return null to maxAttempts
    }

    private fun cooldownPolicyFor(): ClueCooldownPolicy {
        val cooldown = cooldownRepository ?: return ClueCooldownPolicy.Inert
        val onCooldown = cooldown.snapshot(ClueCooldownRepository.DAILY_SCOPE_ID).onCooldown
        return ClueCooldownPolicy.fromSet(onCooldown)
    }

    private fun persistGenerated(
        puzzleId: UUID,
        grid: Grid,
    ) {
        puzzleRepository.getOrCompute(puzzleId) {
            cooldownRepository?.let { cooldown ->
                val usedClues = grid.placements.map { ClueId(it.word.text, it.chosenClue.text) }
                cooldown.recordGeneration(
                    bucketId = ClueCooldownRepository.DAILY_SCOPE_ID,
                    usedClues = usedClues,
                    rollMaxInclusive = cooldownMax,
                )
            }
            StoredPuzzle(
                grid = grid,
                title = title,
                language = language,
                hintsAllowed = hintsAllowed,
                createdAt = clock.instant(),
            )
        }
    }

    /** Outer stride = innerAttempts so each outer attempt owns a disjoint inner-seed block; 1e9 day stride dwarfs any reasonable outer*inner. */
    internal fun seedFor(
        date: LocalDate,
        attempt: Int,
    ): Long = date.toEpochDay() * SEED_DAY_MULTIPLIER + attempt.toLong() * innerAttempts.toLong()

    data class Summary(
        val persistedDates: List<LocalDate>,
        val generatedDates: List<LocalDate>,
        val failedDates: List<LocalDate>,
        val skippedDates: List<LocalDate>,
    )

    companion object {
        const val DEFAULT_WINDOW_DAYS: Int = 7
        const val DEFAULT_MAX_ATTEMPTS: Int = 20
        const val SEED_DAY_MULTIPLIER: Long = 1_000_000_000L

        /** Cron has time; 50 inner Luby restarts per outer seed is the production-grade budget. */
        const val DEFAULT_INNER_ATTEMPTS: Int = 50

        /** Cron-friendly per-attempt timeout (vs production route's 5s default). */
        const val DEFAULT_PER_ATTEMPT_TIMEOUT_MS: Long = 15_000L
    }
}

/** Stubbable seam over [GeneratePuzzleUseCase] so worker tests do not pay CSP solver cost. */
fun interface GridGenerationPort {
    fun generate(
        randomSeed: Long,
        cooldownPolicy: ClueCooldownPolicy,
        attempts: Int,
        perAttemptTimeoutMs: Long,
    ): Grid?
}

fun GeneratePuzzleUseCase.asGridGenerationPort(): GridGenerationPort =
    GridGenerationPort { randomSeed, cooldownPolicy, attempts, perAttemptTimeoutMs ->
        executeWithOutcome(
            cooldownPolicy = cooldownPolicy,
            randomFactory = { attempt -> Random(randomSeed + attempt) },
            attemptsOverride = attempts,
            perAttemptTimeoutMsOverride = perAttemptTimeoutMs,
        ).grid
    }
