package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.Campaign

// Read-only port: open/close happen via direct SQL by the maintainer in v1 (ADR-0059).
interface CampaignRepository {
    suspend fun findOpen(): Campaign?

    // Drives the frontend's lock UI: the open campaign if any, otherwise the most
    // recently opened (closed) campaign; null only when the table is empty.
    suspend fun findCurrent(): Campaign?
}
