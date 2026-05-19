package com.bliss.grid.application.puzzle

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.grid.domain.generation.ClueCooldownRepository
import com.bliss.grid.domain.generation.ClueId
import org.junit.jupiter.api.Test
import java.util.UUID

/** GDPR erasure (ADR-0025 §5); session endpoint covers clue-cooldown rows only. */
class DeleteSessionUseCaseTest {
    private val sessionId: UUID = UUID.randomUUID()

    @Test
    fun `without cooldown repository returns zero`() {
        val useCase = DeleteSessionUseCase()
        assertThat(useCase.execute(sessionId)).isEqualTo(0)
    }

    @Test
    fun `with cooldown repository returns cooldown deletions`() {
        val useCase = DeleteSessionUseCase(cooldownRepository = StubCooldown(deleted = 7))
        assertThat(useCase.execute(sessionId)).isEqualTo(7)
    }

    @Test
    fun `with cooldown repository and zero cooldown rows returns zero`() {
        val useCase = DeleteSessionUseCase(cooldownRepository = StubCooldown(deleted = 0))
        assertThat(useCase.execute(sessionId)).isEqualTo(0)
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
