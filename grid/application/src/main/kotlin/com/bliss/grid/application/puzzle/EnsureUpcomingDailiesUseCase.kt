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

/**
 * Pre-generates and persists daily grids for a rolling [windowDays] window
 * starting at the supplied `today`. Invoked by the `grid-worker --ensure-dailies`
 * CLI which runs as a Kubernetes CronJob (PR C). The HTTP daily route (PR D)
 * will read the persisted-only path so user requests never wait on generation.
 *
 * Sequential execution is mandatory: each successful generation may bump the
 * `DAILY_SCOPE_ID` cooldown counter via [ClueCooldownRepository.recordGeneration],
 * and the next day's generator reads that snapshot to bias clue picks. Running
 * the days in parallel would interleave reads and writes against the shared
 * bucket and corrupt the cooldown ordering, so each date strictly waits for the
 * previous one.
 *
 * A failed day does not abort the loop — `today+6` still gets a chance even if
 * `today+3` exhausts attempts. The caller (worker CLI) decides whether the
 * presence of failed dates warrants a non-zero exit, which the CronJob (PR C)
 * uses to decide whether to alert.
 */
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

    /**
     * Per-attempt random seed for [date]. Multiplying the epoch day by 1000 leaves
     * 1000 distinct seeds per day; the worker CLI runs at most `maxAttempts=20`,
     * so seeds for adjacent days never collide in the attempt range.
     */
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

/**
 * Application-layer port that wraps a single grid-generation attempt with a
 * caller-supplied seed. The wiring layer (`grid-worker`'s `Main.kt` /
 * `grid-api`'s `Module.kt`) adapts this onto [GeneratePuzzleUseCase] which owns
 * the concrete generator + word repository.
 *
 * Defining a port here (instead of taking [GeneratePuzzleUseCase] directly)
 * keeps `EnsureUpcomingDailiesUseCase` testable without spinning up the full
 * CSP-solver stack — tests can pass a stub that returns a precanned grid or
 * `null` to drive the success / exhaustion paths.
 */
fun interface GridGenerationPort {
    fun generate(
        randomSeed: Long,
        cooldownPolicy: ClueCooldownPolicy,
    ): Grid?
}

/**
 * Adapter that drives [GeneratePuzzleUseCase] with a single seeded attempt.
 * The outer retry loop in [EnsureUpcomingDailiesUseCase] handles the per-day
 * attempt budget; this adapter constructs a one-shot `randomFactory` so the
 * generator doesn't double up on retries.
 */
fun GeneratePuzzleUseCase.asGridGenerationPort(): GridGenerationPort =
    GridGenerationPort { randomSeed, cooldownPolicy ->
        executeWithOutcome(
            cooldownPolicy = cooldownPolicy,
            randomFactory = { Random(randomSeed) },
        ).grid
    }
