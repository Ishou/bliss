package com.bliss.grid.worker

import com.bliss.grid.application.puzzle.DailyPuzzleSelector
import com.bliss.grid.application.puzzle.EnsureUpcomingDailiesUseCase
import com.bliss.grid.application.puzzle.GeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.GridGenerationPort
import com.bliss.grid.application.puzzle.LoadOrGeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.PuzzleRepository
import com.bliss.grid.application.puzzle.asGridGenerationPort
import com.bliss.grid.application.puzzle.defaultPuzzleConstraints
import com.bliss.grid.domain.generation.ClueCooldownRepository
import com.bliss.grid.infrastructure.persistence.BlissDatabase
import com.bliss.grid.infrastructure.persistence.CsvWordRepository
import com.bliss.grid.infrastructure.persistence.PostgresClueCooldownRepository
import com.bliss.grid.infrastructure.persistence.PostgresPuzzleRepository
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.system.exitProcess

// Exit codes: 0=all persisted/generated, 1=failedDates non-empty (CronJob alert signal), 2=invalid invocation.
private val log = LoggerFactory.getLogger("com.bliss.grid.worker.Main")

fun main(args: Array<String>) {
    val exit =
        when {
            args.contains("--ensure-dailies") -> runEnsureDailies()
            args.isEmpty() || args.contains("--help") || args.contains("-h") -> {
                printUsage()
                if (args.isEmpty()) 2 else 0
            }
            else -> {
                log.error("worker_unknown_arguments args=\"{}\"", args.joinToString(separator = " "))
                printUsage()
                2
            }
        }
    exitProcess(exit)
}

private fun printUsage() {
    log.info(
        "usage: grid-worker --ensure-dailies | --help " +
            "(--ensure-dailies pre-generates the rolling 7-day window of daily puzzles)",
    )
}

private fun runEnsureDailies(): Int {
    val database =
        BlissDatabase(
            poolName = "grid-worker-hikari",
            maxPoolSize = 2,
            requireUrl = true,
        )
    database.start()
    return try {
        val dataSource = database.dataSource() ?: error("DATABASE_URL produced a null DataSource")
        val puzzleRepository: PuzzleRepository = PostgresPuzzleRepository(dataSource)
        val cooldownRepository: ClueCooldownRepository = PostgresClueCooldownRepository(dataSource)
        val gridGenerationPort = productionGridGenerationPort()
        executeAndExit(puzzleRepository, cooldownRepository, gridGenerationPort)
    } finally {
        database.stop()
    }
}

private fun productionGridGenerationPort(): GridGenerationPort {
    val wordRepository = CsvWordRepository.frenchFromClasspath()
    // maxAttempts=1: outer use case owns the per-day attempt budget.
    val generatePuzzle =
        GeneratePuzzleUseCase(
            wordRepository = wordRepository,
            defaults = defaultPuzzleConstraints(),
            maxAttempts = 1,
        )
    return generatePuzzle.asGridGenerationPort()
}

internal fun executeAndExit(
    puzzleRepository: PuzzleRepository,
    cooldownRepository: ClueCooldownRepository?,
    gridGenerationPort: GridGenerationPort,
    today: LocalDate = LocalDate.now(ZoneOffset.UTC),
): Int {
    val cooldownMax =
        System.getenv("GRID_CLUE_COOLDOWN_MAX")?.toIntOrNull()
            ?: LoadOrGeneratePuzzleUseCase.DEFAULT_COOLDOWN_MAX
    val useCase =
        EnsureUpcomingDailiesUseCase(
            puzzleRepository = puzzleRepository,
            gridGenerationPort = gridGenerationPort,
            dailyPuzzleSelector = DailyPuzzleSelector(),
            cooldownRepository = cooldownRepository,
            cooldownMax = cooldownMax,
        )
    val summary = useCase.execute(today)
    log.info(
        "ensure_dailies_summary today={} dates_total={} dates_persisted={} dates_generated={} dates_failed={} failed_dates=\"{}\"",
        today,
        summary.persistedDates.size + summary.generatedDates.size + summary.failedDates.size,
        summary.persistedDates.size,
        summary.generatedDates.size,
        summary.failedDates.size,
        summary.failedDates.joinToString(separator = ","),
    )
    return if (summary.failedDates.isEmpty()) 0 else 1
}
