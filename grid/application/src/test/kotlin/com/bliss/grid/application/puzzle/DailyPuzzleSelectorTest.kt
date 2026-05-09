package com.bliss.grid.application.puzzle

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Unit tests for [DailyPuzzleSelector]. */
class DailyPuzzleSelectorTest {
    private val selector = DailyPuzzleSelector()

    @Test
    fun `puzzleId is deterministic per date`() {
        val date = LocalDate.of(2026, 5, 9)
        val first = selector.puzzleIdForDate(date)
        val second = selector.puzzleIdForDate(date)
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `puzzleId differs across dates`() {
        val a = selector.puzzleIdForDate(LocalDate.of(2026, 5, 9))
        val b = selector.puzzleIdForDate(LocalDate.of(2026, 5, 10))
        assertThat(a == b).isEqualTo(false)
    }

    @Test
    fun `puzzleId is RFC 9562 UUID v7 with variant 10`() {
        val uuid = selector.puzzleIdForDate(LocalDate.of(2026, 5, 9))
        // Version field — bits 12..15 of the timestamp_high_and_version.
        assertThat(uuid.version()).isEqualTo(7)
        // Variant field — bits 62..63 of the clock_seq_and_node high half;
        // the RFC 4122 variant is 0b10.
        assertThat(uuid.variant()).isEqualTo(2)
    }

    @Test
    fun `gridNumber starts at 1 on the launch day`() {
        // `LAUNCH_EPOCH_DAY` = 2026-01-01.
        assertThat(selector.gridNumberForDate(LocalDate.of(2026, 1, 1))).isEqualTo(1)
    }

    @Test
    fun `gridNumber increments by one per day`() {
        val a = selector.gridNumberForDate(LocalDate.of(2026, 5, 9))
        val b = selector.gridNumberForDate(LocalDate.of(2026, 5, 10))
        assertThat(b - a).isEqualTo(1)
    }

    @Test
    fun `difficulty is hardcoded to facile in v1`() {
        // Heuristics PR will replace this — failing the test is the
        // correct signal that callers need to update.
        assertThat(selector.difficultyForDate(LocalDate.of(2026, 5, 9))).isEqualTo("facile")
    }
}
