package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.WordMetaRepository
import com.bliss.survey.domain.model.WordMeta

class GetLemmaMetaUseCase(
    private val wordMeta: WordMetaRepository,
) {
    suspend fun execute(mot: String): WordMeta? = wordMeta.find(mot)
}
