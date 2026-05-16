package com.bliss.identity.domain.provider

/**
 * The `sub` claim from an OIDC ID token. Opaque IdP-issued identifier;
 * we preserve it verbatim and treat it as the primary key together with
 * the provider.
 */
@JvmInline
value class Subject private constructor(
    val value: String,
) {
    companion object {
        fun of(raw: String): Subject {
            require(raw.isNotBlank()) { "Subject must not be blank." }
            return Subject(raw)
        }
    }
}
