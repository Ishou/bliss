package com.bliss.grid.application.puzzle

import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random

/**
 * Maps a calendar date to (puzzleId, gridNumber, difficulty) for the
 * daily-grid endpoint.
 *
 * The puzzleId is a UUID v7 whose 48-bit timestamp is the date's UTC
 * midnight epoch-ms; the version + variant bits are set per RFC 4122,
 * and the random portions are seeded by the date so the id is fully
 * deterministic for that date. Determinism matters for two reasons:
 *
 *  1. The server's `LoadOrGeneratePuzzleUseCase` is keyed by puzzleId.
 *     Same date → same id → same stored grid, so hint/validate calls
 *     keyed by id from the daily endpoint stay consistent.
 *  2. Two clients hitting `/v1/puzzles/daily` on the same day end up on
 *     the same lobby code's underlying grid (a multiplayer prerequisite
 *     a later PR builds on).
 *
 * `gridNumber` is the day count since [LAUNCH_EPOCH_DAY] inclusive — so
 * day 1 = launch day, day 2 = the next day, etc. Past dates produce
 * positive numbers when later than the anchor; pre-launch dates are
 * permitted and yield non-positive numbers (the route layer can choose
 * to reject them; this class stays pure).
 *
 * `difficulty` is hardcoded to `facile` in v1. A later PR will replace
 * this with heuristics over the generated grid (word density, average
 * answer length, etc.).
 */
class DailyPuzzleSelector(
    // Launch-day anchor — `2026-01-01` was the first ship date of the
    // daily-grid surface. Day 1 = launch day. Bump if the product
    // narrative shifts (re-numbering the past would invalidate links
    // shared on social media; expand-and-contract migration if needed).
    private val launchEpochDay: Long = LAUNCH_EPOCH_DAY,
) {
    fun puzzleIdForDate(date: LocalDate): UUID = deterministicUuidV7(date)

    fun gridNumberForDate(date: LocalDate): Int = (date.toEpochDay() - launchEpochDay).toInt() + 1

    /**
     * Returns the wire-side difficulty token (`facile` / `moyen` /
     * `difficile`) for the given date. Hardcoded to `facile` in v1 — a
     * later PR will replace this with heuristics over the generated grid.
     * The route handler converts the string to `DifficultyDto` at the
     * api boundary.
     */
    fun difficultyForDate(
        @Suppress("UNUSED_PARAMETER") date: LocalDate,
    ): String = "facile"

    private fun deterministicUuidV7(date: LocalDate): UUID {
        val tsMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val seeded = Random(date.toEpochDay())
        val randA = seeded.nextInt(0x1000) // 12 bits
        // 62 bits below the variant: clear top 2 bits.
        val randB = seeded.nextLong() and 0x3FFFFFFFFFFFFFFFL

        // UUID v7 layout (RFC 9562 §5.7):
        //   bits 0..47  = unix-ms timestamp
        //   bits 48..51 = version (0b0111 = 7)
        //   bits 52..63 = rand_a (12 bits)
        //   bits 64..65 = variant (0b10 — RFC 4122)
        //   bits 66..127 = rand_b (62 bits)
        val msb = (tsMs shl 16) or 0x7000L or randA.toLong()
        // ULong literal because 0x8000000000000000 overflows the signed
        // Long range; converting back to Long preserves the bit pattern
        // (the JVM stores it as the negative two's-complement value, which
        // is the correct variant=0b10 high bit pattern).
        val lsb = (0x8000000000000000UL or randB.toULong()).toLong()
        return UUID(msb, lsb)
    }

    companion object {
        // 2026-01-01 UTC. `LocalDate.of(2026, 1, 1).toEpochDay()` =
        // 20454. Inlining the literal so the value is grep-able and the
        // class has no static-initializer cost.
        const val LAUNCH_EPOCH_DAY: Long = 20454L
    }
}
