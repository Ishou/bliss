package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.WordMetaRepository
import com.bliss.survey.domain.model.WordMeta

class InMemoryWordMetaRepository : WordMetaRepository {
    val rows: MutableMap<String, WordMeta> = linkedMapOf()

    override suspend fun find(mot: String): WordMeta? = rows[mot]

    override suspend fun findForUpdate(mot: String): WordMeta? = rows[mot]

    override suspend fun save(meta: WordMeta) {
        rows[meta.mot] = meta
    }
}
