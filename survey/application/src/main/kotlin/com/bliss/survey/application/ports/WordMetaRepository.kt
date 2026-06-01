package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.WordMeta

interface WordMetaRepository {
    suspend fun find(mot: String): WordMeta?

    // Must be called inside a transaction so concurrent merges serialize.
    suspend fun findForUpdate(mot: String): WordMeta?

    suspend fun save(meta: WordMeta)
}
