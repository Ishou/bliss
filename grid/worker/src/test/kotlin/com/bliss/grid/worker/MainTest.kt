package com.bliss.grid.worker

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.bliss.grid.application.puzzle.DailyPuzzleSelector
import com.bliss.grid.application.puzzle.GridGenerationPort
import com.bliss.grid.application.puzzle.PuzzleRepository
import com.bliss.grid.application.puzzle.StoredPuzzle
import com.bliss.grid.domain.generation.ClueCooldownPolicy
import com.bliss.grid.domain.generation.ClueCooldownRepository
import com.bliss.grid.domain.generation.ClueId
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordPlacement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class MainTest {
    private val today: LocalDate = LocalDate.of(2026, 5, 13)
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun attachAppender() {
        logger = LoggerFactory.getLogger("com.bliss.grid.worker.Main") as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        logger.detachAppender(appender)
    }

    @Test
    fun `executeAndExit returns 0 when every day is already persisted`() {
        val selector = DailyPuzzleSelector()
        val repo = PreseededRepo()
        for (offset in 0 until 7) {
            val date = today.plusDays(offset.toLong())
            repo.put(selector.puzzleIdForDate(date), newStoredPuzzle())
        }

        val exit = executeAndExit(repo, NoopCooldownRepo(), ExplodingPort, today = today)

        assertThat(exit).isEqualTo(0)
        val summary = appender.list.single { it.formattedMessage.contains("event=ensure_upcoming_dailies_summary") }
        assertThat(summary.level).isEqualTo(Level.INFO)
        assertThat(summary.formattedMessage).contains("persisted_count=7")
        assertThat(summary.formattedMessage).contains("generated_count=0")
        assertThat(summary.formattedMessage).contains("failed_count=0")
        assertThat(summary.formattedMessage).contains("failed_dates=[]")
        assertThat(summary.formattedMessage).contains("skipped_count=0")
        assertThat(summary.formattedMessage).contains("skipped_dates=[]")
    }

    @Test
    fun `executeAndExit returns 1 and logs failed plus skipped dates when first day fails`() {
        val repo = PreseededRepo()
        val failingPort =
            object : GridGenerationPort {
                override fun generate(
                    randomSeed: Long,
                    cooldownPolicy: ClueCooldownPolicy,
                    attempts: Int,
                    perAttemptTimeoutMs: Long,
                ): Grid? = null
            }

        val exit = executeAndExit(repo, NoopCooldownRepo(), failingPort, today = today)

        assertThat(exit).isEqualTo(1)
        val summary = appender.list.single { it.formattedMessage.contains("event=ensure_upcoming_dailies_summary") }
        // Stop-on-failure: day 1 fails, days 2..7 are skipped (never attempted) so cooldown ordering stays consistent.
        assertThat(summary.formattedMessage).contains("failed_count=1")
        assertThat(summary.formattedMessage).contains("skipped_count=6")
        assertThat(summary.formattedMessage).contains(today.toString())
    }

    private fun newStoredPuzzle(): StoredPuzzle {
        val word = Word(text = "ABCDE", definition = "test")
        val placement =
            WordPlacement(
                word = word,
                cluePosition = Position(Row(0), Column(0)),
                direction = Direction.DOWN_RIGHT,
                chosenClue = word.clues.first(),
            )
        return StoredPuzzle(
            grid = Grid.fromPlacements(width = 5, height = 5, placements = listOf(placement)),
            title = "Grille du jour",
            language = "fr",
            hintsAllowed = 3,
            createdAt = Instant.parse("2026-05-13T00:00:00Z"),
        )
    }

    private class PreseededRepo : PuzzleRepository {
        private val store = HashMap<UUID, StoredPuzzle>()

        fun put(
            id: UUID,
            value: StoredPuzzle,
        ) {
            store[id] = value
        }

        override fun get(puzzleId: UUID): StoredPuzzle? = store[puzzleId]

        override fun getOrCompute(
            puzzleId: UUID,
            factory: () -> StoredPuzzle?,
        ): StoredPuzzle? {
            store[puzzleId]?.let { return it }
            val produced = factory() ?: return null
            return store.computeIfAbsent(puzzleId) { produced }
        }
    }

    private class NoopCooldownRepo : ClueCooldownRepository {
        override fun snapshot(bucketId: UUID): ClueCooldownRepository.Snapshot = ClueCooldownRepository.Snapshot(0L, emptySet())

        override fun recordGeneration(
            bucketId: UUID,
            usedClues: Collection<ClueId>,
            rollMaxInclusive: Int,
        ): Long = 0L

        override fun deleteBySession(bucketId: UUID): Int = 0
    }

    private object ExplodingPort : GridGenerationPort {
        override fun generate(
            randomSeed: Long,
            cooldownPolicy: ClueCooldownPolicy,
            attempts: Int,
            perAttemptTimeoutMs: Long,
        ): Grid = error("GridGenerationPort.generate must not be called when every day is already persisted")
    }
}
