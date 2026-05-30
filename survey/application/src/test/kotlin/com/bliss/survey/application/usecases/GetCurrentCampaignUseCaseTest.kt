package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.bliss.survey.application.ports.CampaignRepository
import com.bliss.survey.domain.model.Campaign
import com.bliss.survey.domain.model.CampaignId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GetCurrentCampaignUseCaseTest {
    private val openedAt = Instant.parse("2026-05-30T10:00:00Z")

    private fun campaign(closed: Instant? = null): Campaign =
        Campaign(
            id = CampaignId(UUID.randomUUID()),
            batchLabel = "round-7",
            openedAt = openedAt,
            closedAt = closed,
        )

    @Test
    fun `delegates to repository findCurrent`() =
        runTest {
            val expected = campaign()
            val repo =
                object : CampaignRepository {
                    override suspend fun findOpen(): Campaign? = expected

                    override suspend fun findCurrent(): Campaign? = expected
                }
            val useCase = GetCurrentCampaignUseCase(repo)

            assertThat(useCase.execute()).isEqualTo(expected)
        }

    @Test
    fun `returns null when no campaign exists`() =
        runTest {
            val repo =
                object : CampaignRepository {
                    override suspend fun findOpen(): Campaign? = null

                    override suspend fun findCurrent(): Campaign? = null
                }
            val useCase = GetCurrentCampaignUseCase(repo)

            assertThat(useCase.execute()).isNull()
        }
}
