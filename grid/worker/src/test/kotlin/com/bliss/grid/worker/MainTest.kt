package com.bliss.grid.worker

import assertk.assertThat
import assertk.assertions.isEqualTo
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
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MainTest {
    private val today: LocalDate = LocalDate.of(2026, 5, 13)

    @Test
    fun `executeAndExit returns 0 when every day is already persisted`() {
        val selector = DailyPuzzleSelector()
        val repo = PreseededRepo()
        for (offset in 0 until 7) {
            val date = today.plusDays(offset.toLong())
            repo.put(selector.puzzleIdForDate(date), newStoredPuzzle())
        }
        val cooldown = NoopCooldownRepo()
        val explodingPort = ExplodingPort

        val exit = executeAndExit(repo, cooldown, explodingPort, today = today)

        assertThat(exit).isEqualTo(0)
    }

    @Test
    fun `executeAndExit returns 1 when at least one day fails`() {
        val repo = PreseededRepo()
        val cooldown = NoopCooldownRepo()
        // Port always fails — every day exhausts attempts.
        val failingPort =
            object : GridGenerationPort {
                override fun generate(
                    randomSeed: Long,
                    cooldownPolicy: ClueCooldownPolicy,
                ): Grid? = null
            }

        val exit = executeAndExit(repo, cooldown, failingPort, today = today)

        assertThat(exit).isEqualTo(1)
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
        private val store = ConcurrentHashMap<UUID, StoredPuzzle>()

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
        ): Grid = error("GridGenerationPort.generate must not be called when every day is already persisted")
    }
}
