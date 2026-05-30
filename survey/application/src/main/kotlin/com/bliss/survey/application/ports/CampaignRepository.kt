package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.Campaign
import com.bliss.survey.domain.model.CampaignId

// Read-only port: open/close happen via direct SQL by the maintainer in v1 (ADR-0059).
interface CampaignRepository {
    suspend fun findOpen(): Campaign?

    // Open campaign if any; otherwise the most recently opened (closed) row; null only when table is empty.
    suspend fun findCurrent(): Campaign?

    suspend fun findById(id: CampaignId): Campaign?
}
