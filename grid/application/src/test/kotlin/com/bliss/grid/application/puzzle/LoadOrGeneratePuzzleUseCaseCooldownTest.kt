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
        assertThat(tracker.snapshotCalls.size >= 2).isTrue()
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
            // Multiple shift constants → ~100 distinct words per length,
            // enough variety for the bitmask CSP to find consistent
            // assignments even with non-trivial interior crossings.
            val shifts = intArrayOf(1, 5, 7, 11, 17, 23)
            return shifts
                .flatMap { shift ->
                    (0..25).map { n ->
                        val chars = CharArray(length) { i -> pattern[i] ?: 'A' }
                        unconstrained.forEachIndexed { idx, pos -> chars[pos] = 'A' + (n + idx * shift) % 26 }
                        Word(String(chars), "test definition")
                    }
                }.distinctBy { it.text }
        }
    }
}
