package com.bliss.grid.api.infrastructure.words

import com.bliss.grid.api.infrastructure.Database
import com.bliss.grid.domain.generation.WordRepository
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * Feature-flag selector for the API's [WordRepository] implementation
 * (CLAUDE.md "Feature flags: deploy dark, release bright"; ADR-0013 §8).
 *
 * `WORDS_SOURCE` (env var or same-named JVM system property) selects:
 *
 *   * `resource` (default) — classpath-backed [ResourceWordRepository].
 *     Zero behaviour change; what prod runs today.
 *   * `database` — [DatabaseWordRepository]. Requires `DATABASE_URL`;
 *     fails fast at startup otherwise. The `words` table is empty
 *     in prod until ADR-0013 follow-up #5 (seed run) lands, so the
 *     default stays `resource` until that PR explicitly flips it.
 *
 * Flag expiry: **2026-07-31**. After the seed run + a cleanup PR,
 * inline [DatabaseWordRepository] in `Module.kt` and delete this
 * selector. [WordsSourceTest] asserts the expiry hasn't passed.
 */
internal object WordsSource {
    private val log = LoggerFactory.getLogger(WordsSource::class.java)

    internal const val ENV_VAR: String = "WORDS_SOURCE"
    internal const val RESOURCE: String = "resource"
    internal const val DATABASE: String = "database"
    internal val VALID_VALUES: Set<String> = setOf(RESOURCE, DATABASE)

    /** See KDoc above; bump only with a fresh review of the flag's purpose. */
    internal val EXPIRY: LocalDate = LocalDate.of(2026, 7, 31)

    /** Reads the flag, validates, and constructs the chosen repository. */
    fun resolve(): WordRepository {
        val raw = readEnv()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: RESOURCE
        return when (raw) {
            RESOURCE -> {
                log.info("words_source_selected source={} reason=default_or_explicit", RESOURCE)
                ResourceWordRepository.frenchFromClasspath()
            }
            DATABASE -> {
                val ds =
                    Database.dataSource()
                        ?: error(
                            "$ENV_VAR=$DATABASE requires DATABASE_URL to be set; " +
                                "either unset $ENV_VAR (defaults to '$RESOURCE') or provide DATABASE_URL.",
                        )
                log.info("words_source_selected source={} reason=explicit", DATABASE)
                DatabaseWordRepository(ds)
            }
            else ->
                throw IllegalArgumentException(
                    "$ENV_VAR='$raw' is not a valid value; expected one of $VALID_VALUES.",
                )
        }
    }

    private fun readEnv(): String? = System.getenv(ENV_VAR) ?: System.getProperty(ENV_VAR)
}
