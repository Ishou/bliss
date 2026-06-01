package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.MaintainerRoleRepository
import com.bliss.survey.application.ports.TransactionManager
import com.bliss.survey.application.ports.WordMetaRepository
import com.bliss.survey.application.text.GlossNormalizer
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.model.WordMeta

sealed interface UpsertSubTagsResult {
    data object Ok : UpsertSubTagsResult

    data object Forbidden : UpsertSubTagsResult
}

class UpsertSubTagsUseCase(
    private val wordMeta: WordMetaRepository,
    private val maintainerRoles: MaintainerRoleRepository,
    private val clock: Clock,
    private val tx: TransactionManager,
) {
    suspend fun execute(
        mot: String,
        subTags: List<String>,
        userId: UserId,
    ): UpsertSubTagsResult {
        if (maintainerRoles.find(userId)?.role != "maintainer") return UpsertSubTagsResult.Forbidden
        val cleaned = dedupByNormalized(subTags)
        tx.inTransaction {
            // findForUpdate enrolls in the same row-lock used by mergeIntoSenseInventory; senseInventory is preserved.
            val existing = wordMeta.findForUpdate(mot)
            wordMeta.save(
                WordMeta(
                    mot = mot,
                    subTags = cleaned,
                    senseInventory = existing?.senseInventory ?: emptyList(),
                    updatedAt = clock.now(),
                ),
            )
        }
        return UpsertSubTagsResult.Ok
    }

    private fun dedupByNormalized(items: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(items.size)
        for (raw in items) {
            val key = GlossNormalizer.normalize(raw)
            if (seen.add(key)) out += raw
        }
        return out
    }
}
