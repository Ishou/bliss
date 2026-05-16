package com.bliss.identity.domain.user

import java.util.UUID

@JvmInline
value class UserId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun parse(raw: String): UserId = UserId(UUID.fromString(raw))
    }
}
