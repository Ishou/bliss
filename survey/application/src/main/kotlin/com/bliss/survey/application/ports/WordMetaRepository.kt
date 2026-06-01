package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.WordMeta

interface WordMetaRepository {
    suspend fun find(mot: String): WordMeta?

    /** Ensures the row exists then locks it for update; must be called inside a transaction. */
    suspend fun findForUpdate(mot: String): WordMeta?

    suspend fun save(meta: WordMeta)
}
