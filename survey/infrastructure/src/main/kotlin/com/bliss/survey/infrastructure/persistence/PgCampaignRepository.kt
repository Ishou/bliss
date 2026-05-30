package com.bliss.survey.infrastructure.persistence

import com.bliss.survey.application.ports.CampaignRepository
import com.bliss.survey.domain.model.Campaign
import com.bliss.survey.domain.model.CampaignId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

class PgCampaignRepository(
    private val dataSource: DataSource,
) : CampaignRepository {
    override suspend fun findOpen(): Campaign? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(FIND_OPEN_SQL).use { stmt ->
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toCampaign() else null }
                }
            }
        }

    override suspend fun findCurrent(): Campaign? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(FIND_CURRENT_SQL).use { stmt ->
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toCampaign() else null }
                }
            }
        }

    private fun ResultSet.toCampaign(): Campaign =
        Campaign(
            id = CampaignId(getObject("campaign_id", UUID::class.java)),
            batchLabel = getString("batch_label"),
            openedAt = getTimestamp("opened_at").toInstant(),
            closedAt = getTimestamp("closed_at")?.toInstant(),
        )

    private companion object {
        const val FIND_OPEN_SQL =
            "SELECT campaign_id, batch_label, opened_at, closed_at FROM campaigns WHERE closed_at IS NULL LIMIT 1"

        // Open campaign first, otherwise the most recently opened — uses campaigns_opened_at_idx.
        const val FIND_CURRENT_SQL =
            "SELECT campaign_id, batch_label, opened_at, closed_at FROM campaigns ORDER BY opened_at DESC LIMIT 1"
    }
}
