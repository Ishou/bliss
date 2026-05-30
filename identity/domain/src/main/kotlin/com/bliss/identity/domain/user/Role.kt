package com.bliss.identity.domain.user

/** Authz primitive (ADR-0060). `wire` is the stable DB + event-payload spelling. */
enum class Role(
    val wire: String,
) {
    PLAYER("player"),
    MAINTAINER("maintainer"),
    ;

    companion object {
        fun fromWire(raw: String): Role =
            entries.firstOrNull { it.wire == raw }
                ?: throw IllegalArgumentException("Unknown role: $raw")
    }
}
