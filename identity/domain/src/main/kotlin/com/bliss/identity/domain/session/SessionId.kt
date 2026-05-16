package com.bliss.identity.domain.session

import java.util.UUID

@JvmInline
value class SessionId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun parse(raw: String): SessionId = SessionId(UUID.fromString(raw))
    }
}
