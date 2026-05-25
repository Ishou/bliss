package com.bliss.survey.domain.model

import java.util.UUID

@JvmInline
value class ItemId(
    val value: UUID,
)

@JvmInline
value class RatingId(
    val value: UUID,
)

@JvmInline
value class UserId(
    val value: UUID,
)
