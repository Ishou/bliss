package com.bliss.grid.api.infrastructure.words

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import com.bliss.grid.api.infrastructure.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit tests for the [WordsSource] feature flag (ADR-0013 §8 + CLAUDE.md
 * "Feature flags: deploy dark, release bright").
 *
 * No Docker / no Postgres required — the database branch only asserts
 * fail-fast behaviour when `DATABASE_URL` is absent. Real-DB coverage of
 * the chosen impl lives in [DatabaseWordRepositoryTest].
 */
class WordsSourceTest {
    @BeforeEach
    @AfterEach
    fun reset() {
        System.clearProperty(WordsSource.ENV_VAR)
        System.clearProperty("DATABASE_URL")
        Database.stopForTesting()
    }

    @Test
    fun `default WORDS_SOURCE wires the resource-backed repository`() {
        val repo = WordsSource.resolve()
        assertThat(repo).isInstanceOf(ResourceWordRepository::class)
    }

    @Test
    fun `WORDS_SOURCE=database without DATABASE_URL fails fast with a clear error`() {
        System.setProperty(WordsSource.ENV_VAR, "database")
        // DATABASE_URL is unset (cleared in @BeforeEach); Database.start() is a no-op.
        Database.start()
        val failure = assertFailure { WordsSource.resolve() }
        // The error message must name both the env var and the flag value
        // so an operator can act on it without grepping the source.
        failure.isInstanceOf(IllegalStateException::class)
        failure.transform { it.message ?: "" }.contains("DATABASE_URL")
        failure.transform { it.message ?: "" }.contains("database")
    }

    @Test
    fun `unknown WORDS_SOURCE value fails fast and lists valid values`() {
        System.setProperty(WordsSource.ENV_VAR, "redis")
        val failure = assertFailure { WordsSource.resolve() }
        failure.isInstanceOf(IllegalArgumentException::class)
        failure.transform { it.message ?: "" }.contains("redis")
        failure.transform { it.message ?: "" }.contains("resource")
        failure.transform { it.message ?: "" }.contains("database")
    }

    /**
     * Per CLAUDE.md: "Flags have expiration dates — expired flags fail CI."
     * After 2026-07-31 the seed run + cleanup PR is overdue; revisit
     * [WordsSource] (likely inline `DatabaseWordRepository` and delete the
     * flag, the resource-backed repo, and `fr.json`) rather than bumping
     * this date silently.
     */
    @Test
    fun `WORDS_SOURCE flag has not yet expired`() {
        assertThat(LocalDate.now()).isLessThan(WordsSource.EXPIRY)
    }
}
