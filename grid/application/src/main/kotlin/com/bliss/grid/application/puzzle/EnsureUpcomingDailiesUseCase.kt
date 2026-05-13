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

// Sequential: cooldown snapshot from day N biases day N+1; parallel runs corrupt the ordering.
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
) {
    private val log = LoggerFactory.getLogger(EnsureUpcomingDailiesUseCase::class.java)

    fun execute(today: LocalDate): Summary {
        val persistedDates = mutableListOf<LocalDate>()
        val generatedDates = mutableListOf<LocalDate>()
        val failedDates = mutableListOf<LocalDate>()

        for (offset in 0 until windowDays) {
            val date = today.plusDays(offset.toLong())
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
        )
    }

    private fun generateForDate(date: LocalDate): Pair<Grid?, Int> {
        val cooldownPolicy = cooldownPolicyFor()
        for (attempt in 0 until maxAttempts) {
            val grid =
                gridGenerationPort.generate(
                    randomSeed = seedFor(date, attempt),
                    cooldownPolicy = cooldownPolicy,
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

    // Stride 1000: 20 attempts per day never overlaps adjacent days' seed range.
    internal fun seedFor(
        date: LocalDate,
        attempt: Int,
    ): Long = date.toEpochDay() * SEED_DAY_MULTIPLIER + attempt

    data class Summary(
        val persistedDates: List<LocalDate>,
        val generatedDates: List<LocalDate>,
        val failedDates: List<LocalDate>,
    )

    companion object {
        const val DEFAULT_WINDOW_DAYS: Int = 7
        const val DEFAULT_MAX_ATTEMPTS: Int = 20

        /** See [seedFor] kdoc for the choice of stride. */
        const val SEED_DAY_MULTIPLIER: Long = 1000L
    }
}

// Port: decouples EnsureUpcomingDailiesUseCase from the CSP solver so tests can stub it.
fun interface GridGenerationPort {
    fun generate(
        randomSeed: Long,
        cooldownPolicy: ClueCooldownPolicy,
    ): Grid?
}

fun GeneratePuzzleUseCase.asGridGenerationPort(): GridGenerationPort =
    GridGenerationPort { randomSeed, cooldownPolicy ->
        executeWithOutcome(
            cooldownPolicy = cooldownPolicy,
            randomFactory = { Random(randomSeed) },
        ).grid
    }
