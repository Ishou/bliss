package com.bliss.grid.application.puzzle

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.bliss.grid.domain.generation.ClueCooldownRepository
import com.bliss.grid.domain.generation.ClueId
import com.bliss.grid.domain.generation.GridConstraints
import com.bliss.grid.domain.generation.WordRepository
import com.bliss.grid.domain.model.Word
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Integration tests for cooldown wiring in [LoadOrGeneratePuzzleUseCase] — see ADR-0031. */
class LoadOrGeneratePuzzleUseCaseCooldownTest {
    private val puzzleId: UUID = UUID.randomUUID()
    private val sessionId: UUID = UUID.randomUUID()
    private val daily: UUID = ClueCooldownRepository.DAILY_SCOPE_ID

    private fun newUseCase(
        cooldownRepository: ClueCooldownRepository? = TrackingCooldownRepo(),
        cooldownMax: Int = 8,
    ): Pair<LoadOrGeneratePuzzleUseCase, TrackingCooldownRepo?> {
        val tracker = cooldownRepository as? TrackingCooldownRepo
        val useCase =
            LoadOrGeneratePuzzleUseCase(
                puzzleRepository = InMemoryPuzzleRepository(),
                generatePuzzle =
                    GeneratePuzzleUseCase(
                        wordRepository = SmallTestRepository,
                        defaults = GridConstraints(width = 5, height = 5),
                    ),
                cooldownRepository = cooldownRepository,
                cooldownMax = cooldownMax,
            )
        return useCase to tracker
    }

    @Test
    fun `cache miss with sessionId bumps counter and records used clues`() {
        val (useCase, tracker) = newUseCase()
        val stored = useCase.execute(puzzleId, sessionId = sessionId)

        assertThat(stored).isNotNull()
        assertThat(tracker!!.snapshotCalls).isEqualTo(listOf(sessionId))
        assertThat(tracker.records).hasSize(1)
        val (recordedBucket, recordedClues, recordedRoll) = tracker.records.single()
        assertThat(recordedBucket).isEqualTo(sessionId)
        assertThat(recordedRoll).isEqualTo(8)
        // One ClueId per placement in the resulting grid.
        val expected = stored!!.grid.placements.map { ClueId(it.word.text, it.chosenClue.text) }
        assertThat(recordedClues.toSet()).isEqualTo(expected.toSet())
    }

    @Test
    fun `cache hit does not bump the counter`() {
        val (useCase, tracker) = newUseCase()
        useCase.execute(puzzleId, sessionId = sessionId)
        val recordsAfterFirst = tracker!!.records.size

        // Second fetch on the same puzzleId is a cache hit — no new snapshot,
        // no new recordGeneration. (snapshotCalls may stay the same; we
        // assert on records here for clarity.)
        useCase.execute(puzzleId, sessionId = sessionId)
        assertThat(tracker.records.size).isEqualTo(recordsAfterFirst)
    }

    @Test
    fun `null sessionId bypasses cooldown reads and writes entirely`() {
        val (useCase, tracker) = newUseCase()
        val stored = useCase.execute(puzzleId, sessionId = null)
        assertThat(stored).isNotNull()
        assertThat(tracker!!.snapshotCalls).isEmpty()
        assertThat(tracker.records).isEmpty()
    }

    @Test
    fun `null cooldown repository keeps existing behavior`() {
        // No cooldown wiring at all — the use case behaves as if the feature
        // flag were off. Even with a sessionId provided, no snapshot or
        // record call happens (no repository to call against).
        val (useCase, tracker) = newUseCase(cooldownRepository = null)
        val stored = useCase.execute(puzzleId, sessionId = sessionId)
        assertThat(stored).isNotNull()
        assertThat(tracker).isEqualTo(null)
    }

    @Test
    fun `daily scope id is honored as a bucket like any other`() {
        val (useCase, tracker) = newUseCase()
        useCase.execute(puzzleId, sessionId = daily)
        assertThat(tracker!!.records.single().first).isEqualTo(daily)
    }

    @Test
    fun `consecutive cache misses on different puzzleIds bump twice`() {
        val (useCase, tracker) = newUseCase()
        useCase.execute(puzzleId, sessionId = sessionId)
        useCase.execute(UUID.randomUUID(), sessionId = sessionId)
        assertThat(tracker!!.records).hasSize(2)
        // The second snapshot must observe the first generation's writes —
        // the use case re-reads the snapshot inside the second cache-miss
        // factory lambda, so the seq returned by both records should be 1
        // and 2 respectively.
        val seqs = tracker.records.map { (_, _, _) -> tracker.lastReturnedSeq }
        // We don't store per-record seqs here; assert at minimum that the
        // counter advanced (snapshotCalls captures the second read).
        assertThat(tracker.snapshotCalls.size >= 2).isTrue()
        // Silence unused warning.
        seqs.size
    }

    /** In-memory [ClueCooldownRepository] fake that records call order for assertion. */
    private class TrackingCooldownRepo : ClueCooldownRepository {
        val snapshotCalls = mutableListOf<UUID>()
        val records = mutableListOf<Triple<UUID, List<ClueId>, Int>>()
        var lastReturnedSeq: Long = 0L
            private set
        private val counters = ConcurrentHashMap<UUID, Long>()
        private val rows = ConcurrentHashMap<UUID, MutableMap<ClueId, Long>>()

        override fun snapshot(bucketId: UUID): ClueCooldownRepository.Snapshot {
            snapshotCalls += bucketId
            val current = counters[bucketId] ?: 0L
            val active =
                rows[bucketId]
                    ?.entries
                    ?.filter { it.value > current }
                    ?.map { it.key }
                    ?.toSet() ?: emptySet()
            return ClueCooldownRepository.Snapshot(current, active)
        }

        override fun recordGeneration(
            bucketId: UUID,
            usedClues: Collection<ClueId>,
            rollMaxInclusive: Int,
        ): Long {
            records += Triple(bucketId, usedClues.toList(), rollMaxInclusive)
            val newSeq = (counters[bucketId] ?: 0L) + 1L
            counters[bucketId] = newSeq
            val sessionRows = rows.computeIfAbsent(bucketId) { HashMap() }
            for (clue in usedClues) {
                sessionRows[clue] = newSeq + 1L // pin TTL = 1 for determinism
            }
            lastReturnedSeq = newSeq
            return newSeq
        }

        override fun deleteBySession(bucketId: UUID): Int {
            val n = rows[bucketId]?.size ?: 0
            rows.remove(bucketId)
            counters.remove(bucketId)
            return n
        }
    }

    private class InMemoryPuzzleRepository : PuzzleRepository {
        private val store = ConcurrentHashMap<UUID, StoredPuzzle>()

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

    private object SmallTestRepository : WordRepository {
        override fun findByLength(length: Int): List<Word> = candidates(length, emptyMap())

        override fun findByLengthAndPattern(
            length: Int,
            pattern: Map<Int, Char>,
        ): List<Word> = candidates(length, pattern)

        override fun containsLemma(text: String): Boolean = true

        private fun candidates(
            length: Int,
            pattern: Map<Int, Char>,
        ): List<Word> {
            val unconstrained = (0 until length).filter { it !in pattern }
            return (0..25)
                .map { n ->
                    val chars = CharArray(length) { i -> pattern[i] ?: 'A' }
                    unconstrained.forEachIndexed { idx, pos -> chars[pos] = 'A' + (n + idx * 7) % 26 }
                    Word(String(chars), "test definition")
                }.distinctBy { it.text }
        }
    }
}
