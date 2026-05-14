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
import org.slf4j.MDC
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("com.bliss.grid.worker.Main")

fun main(args: Array<String>) {
    MDC.put("run_id", UUID.randomUUID().toString())
    val exit =
        when {
            args.contains("--help") || args.contains("-h") -> {
                printUsage()
                0
            }
            args.isEmpty() -> {
                log.error("event=worker_no_arguments")
                printUsage()
                1
            }
            args.contains("--ensure-dailies") -> runEnsureDailies()
            else -> {
                log.error("event=worker_unknown_arguments args=\"{}\"", args.joinToString(separator = " "))
                printUsage()
                1
            }
        }
    exitProcess(exit)
}

private fun printUsage() {
    log.info("usage: grid-worker --ensure-dailies | --help")
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
        executeAndExit(puzzleRepository, cooldownRepository, productionGridGenerationPort())
    } finally {
        database.stop()
    }
}

private fun productionGridGenerationPort(): GridGenerationPort {
    val wordRepository = CsvWordRepository.frenchFromClasspath()
    // EnsureUpcomingDailiesUseCase passes per-call attempts and timeout overrides to
    // GeneratePuzzleUseCase via the port, so the constructor maxAttempts is unused at runtime.
    val generatePuzzle =
        GeneratePuzzleUseCase(
            wordRepository = wordRepository,
            defaults = defaultPuzzleConstraints(),
        )
    return generatePuzzle.asGridGenerationPort()
}

internal fun executeAndExit(
    puzzleRepository: PuzzleRepository,
    cooldownRepository: ClueCooldownRepository,
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
        "event=ensure_upcoming_dailies_summary persisted_count={} generated_count={} failed_count={} skipped_count={} failed_dates=[{}] skipped_dates=[{}]",
        summary.persistedDates.size,
        summary.generatedDates.size,
        summary.failedDates.size,
        summary.skippedDates.size,
        summary.failedDates.joinToString(separator = ","),
        summary.skippedDates.joinToString(separator = ","),
    )
    return if (summary.failedDates.isEmpty() && summary.skippedDates.isEmpty()) 0 else 1
}
