package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.PairRating
import com.bliss.survey.domain.model.PairRatingId

interface PairRatingRepository {
    // returns false when the partial unique index rejects a duplicate auth submission
    suspend fun insert(rating: PairRating): Boolean

    suspend fun deleteById(id: PairRatingId)
}
