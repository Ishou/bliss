package com.bliss.grid.domain.generation

/**
 * Injectable wall-clock + monotonic-clock port for the generation pipeline.
 *
 * Domain code must not reach for [System.currentTimeMillis] or [System.nanoTime]
 * directly — those calls make deadline-edge behaviour untestable. Inject a
 * [Clock] instead. Production callers pass [SystemClock] (the default); tests
 * can pass a controllable fake to drive timeouts deterministically.
 */
interface Clock {
    /** Wall-clock milliseconds since the epoch. Used for deadline comparisons. */
    fun currentTimeMillis(): Long

    /** Monotonic nanoseconds. Used for elapsed-time measurements (per-phase metrics). */
    fun nanoTime(): Long
}

/** Production clock — thin wrapper over `java.lang.System`. */
object SystemClock : Clock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun nanoTime(): Long = System.nanoTime()
}
