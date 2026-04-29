package com.bliss.grid.domain.generation

/**
 * Number of high-frequency candidates to randomly shuffle at the head of a domain
 * before iterating in deterministic frequency order. Used by both the slot-based
 * filler ([SkeletonFiller]) and the legacy greedy generator (`GridGenerator.generateGreedy`).
 *
 * Small enough to keep the shuffle cheap; large enough that consecutive puzzle
 * generations don't repeat identical word picks. ~30 common matches across multiple
 * candidate slots per request gives manageable variety without losing the
 * frequency bias that helps the CSP converge.
 */
internal const val HEAD_SHUFFLE_SIZE: Int = 30
