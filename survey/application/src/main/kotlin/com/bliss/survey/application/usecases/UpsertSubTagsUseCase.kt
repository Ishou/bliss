package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.MaintainerRoleRepository
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
) {
    suspend fun execute(
        mot: String,
        subTags: List<String>,
        userId: UserId,
    ): UpsertSubTagsResult {
        if (maintainerRoles.find(userId)?.role != "maintainer") return UpsertSubTagsResult.Forbidden
        val cleaned = dedupByNormalized(subTags)
        val existing = wordMeta.find(mot)
        val updated =
            WordMeta(
                mot = mot,
                subTags = cleaned,
                senseInventory = existing?.senseInventory ?: emptyList(),
                updatedAt = clock.now(),
            )
        wordMeta.save(updated)
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
