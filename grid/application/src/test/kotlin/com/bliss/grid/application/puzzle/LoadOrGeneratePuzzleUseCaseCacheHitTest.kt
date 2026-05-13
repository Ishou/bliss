package com.bliss.grid.application.puzzle

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import com.bliss.grid.application.analytics.AnalyticsEventSink
import com.bliss.grid.domain.analytics.AnalyticsEvent
import com.bliss.grid.domain.generation.ClueCooldownRepository
import com.bliss.grid.domain.generation.ClueId
import com.bliss.grid.domain.generation.GridConstraints
import com.bliss.grid.domain.generation.WordRepository
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordPlacement
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LoadOrGeneratePuzzleUseCaseCacheHitTest {
    private val puzzleId: UUID = UUID.randomUUID()
    private val daily: UUID = ClueCooldownRepository.DAILY_SCOPE_ID

    @Test
    fun `cache hit returns persisted puzzle without invoking the generator`() {
        val repo = InMemoryPuzzleRepository()
        val seed = newStoredPuzzle()
        repo.put(puzzleId, seed)

        val cooldown = TrackingCooldownRepo()
        val analytics = TrackingAnalyticsSink()
        val useCase =
            LoadOrGeneratePuzzleUseCase(
                puzzleRepository = repo,
                generatePuzzle =
                    GeneratePuzzleUseCase(
                        wordRepository = ExplodingWordRepository,
                        defaults = GridConstraints(width = 5, height = 5),
                    ),
                cooldownRepository = cooldown,
                analyticsEventSink = analytics,
            )

        val stored = useCase.execute(puzzleId, sessionId = daily)

        assertThat(stored).isNotNull()
        assertThat(stored!!).isSameInstanceAs(seed)
        // Generation gated behind the lambda; if it ever ran, ExplodingWordRepository would throw.
        assertThat(cooldown.snapshotCalls).isEmpty()
        assertThat(cooldown.recordCalls).isEmpty()
        assertThat(analytics.records).isEmpty()
    }

    @Test
    fun `cache hit with null sessionId still skips the generator`() {
        val repo = InMemoryPuzzleRepository()
        repo.put(puzzleId, newStoredPuzzle())
        val analytics = TrackingAnalyticsSink()
        val useCase =
            LoadOrGeneratePuzzleUseCase(
                puzzleRepository = repo,
                generatePuzzle =
                    GeneratePuzzleUseCase(
                        wordRepository = ExplodingWordRepository,
                        defaults = GridConstraints(width = 5, height = 5),
                    ),
                cooldownRepository = TrackingCooldownRepo(),
                analyticsEventSink = analytics,
            )

        useCase.execute(puzzleId, sessionId = null)

        // Same guard: ExplodingWordRepository would have thrown on any generator entry.
        assertThat(analytics.records).isEmpty()
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

    private class TrackingCooldownRepo : ClueCooldownRepository {
        val snapshotCalls = mutableListOf<UUID>()
        val recordCalls = mutableListOf<UUID>()

        override fun snapshot(bucketId: UUID): ClueCooldownRepository.Snapshot {
            snapshotCalls += bucketId
            return ClueCooldownRepository.Snapshot(0L, emptySet())
        }

        override fun recordGeneration(
            bucketId: UUID,
            usedClues: Collection<ClueId>,
            rollMaxInclusive: Int,
        ): Long {
            recordCalls += bucketId
            return 1L
        }

        override fun deleteBySession(bucketId: UUID): Int = 0
    }

    private class TrackingAnalyticsSink : AnalyticsEventSink {
        val records = mutableListOf<AnalyticsEvent>()

        override fun record(
            event: AnalyticsEvent,
            sessionId: UUID?,
        ) {
            records += event
        }
    }

    private class InMemoryPuzzleRepository : PuzzleRepository {
        private val store = ConcurrentHashMap<UUID, StoredPuzzle>()

        fun put(
            puzzleId: UUID,
            puzzle: StoredPuzzle,
        ) {
            store[puzzleId] = puzzle
        }

        override fun get(puzzleId: UUID): StoredPuzzle? = store[puzzleId]

        override fun getOrCompute(
            puzzleId: UUID,
            factory: () -> StoredPuzzle?,
        ): StoredPuzzle? {
            store[puzzleId]?.let { return it }
            val computed = factory() ?: return null
            return store.computeIfAbsent(puzzleId) { computed }
        }
    }

    private object ExplodingWordRepository : WordRepository {
        override fun findByLength(length: Int): List<Word> = error("WordRepository.findByLength must not be called on cache-hit path")

        override fun findByLengthAndPattern(
            length: Int,
            pattern: Map<Int, Char>,
        ): List<Word> = error("WordRepository.findByLengthAndPattern must not be called on cache-hit path")

        override fun containsLemma(text: String): Boolean = error("WordRepository.containsLemma must not be called on cache-hit path")
    }
}
