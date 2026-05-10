package com.bliss.grid.application.puzzle

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.grid.domain.generation.ClueCooldownRepository
import com.bliss.grid.domain.generation.ClueId
import org.junit.jupiter.api.Test
import java.util.UUID

/** GDPR erasure (ADR-0025 §5) extends to clue cooldown rows when wired — see ADR-0031. */
class DeleteSessionUseCaseTest {
    private val sessionId: UUID = UUID.randomUUID()

    @Test
    fun `without cooldown repository returns only the hint usage count`() {
        val useCase =
            DeleteSessionUseCase(
                hintUsageRepository = StubHintUsage(deleted = 3),
            )
        assertThat(useCase.execute(sessionId)).isEqualTo(3)
    }

    @Test
    fun `with cooldown repository sums hint usage and cooldown deletions`() {
        val useCase =
            DeleteSessionUseCase(
                hintUsageRepository = StubHintUsage(deleted = 3),
                cooldownRepository = StubCooldown(deleted = 7),
            )
        assertThat(useCase.execute(sessionId)).isEqualTo(10)
    }

    @Test
    fun `with cooldown repository and zero cooldown rows returns hint count`() {
        val useCase =
            DeleteSessionUseCase(
                hintUsageRepository = StubHintUsage(deleted = 0),
                cooldownRepository = StubCooldown(deleted = 0),
            )
        assertThat(useCase.execute(sessionId)).isEqualTo(0)
    }

    private class StubHintUsage(
        private val deleted: Int,
    ) : HintUsageRepository {
        override fun trySpend(
            puzzleId: UUID,
            sessionId: UUID,
            hintsAllowed: Int,
        ): Int? = null

        override fun deleteBySession(sessionId: UUID): Int = deleted
    }

    private class StubCooldown(
        private val deleted: Int,
    ) : ClueCooldownRepository {
        override fun snapshot(bucketId: UUID): ClueCooldownRepository.Snapshot =
            ClueCooldownRepository.Snapshot(currentSeq = 0L, onCooldown = emptySet())

        override fun recordGeneration(
            bucketId: UUID,
            usedClues: Collection<ClueId>,
            rollMaxInclusive: Int,
        ): Long = 0L

        override fun deleteBySession(bucketId: UUID): Int = deleted
    }
}
