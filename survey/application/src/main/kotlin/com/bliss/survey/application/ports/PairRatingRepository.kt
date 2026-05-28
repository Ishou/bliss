package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.PairRating

interface PairRatingRepository {
    /**
     * Insert a pair rating; returns false if the partial unique index rejected the row as a duplicate
     * (authed caller re-rating the same unordered pair). Anon callers always insert.
     */
    suspend fun insert(rating: PairRating): Boolean
}
