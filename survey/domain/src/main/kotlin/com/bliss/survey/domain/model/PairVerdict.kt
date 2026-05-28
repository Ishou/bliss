package com.bliss.survey.domain.model

// Full wire-level verdict set; route layer dispatches to ratings vs pair_ratings vs no-op (ADR-0056 amendment 2026-05-28).
enum class PairVerdict {
    LEFT_WINS,
    RIGHT_WINS,
    BOTH_GOOD,
    BOTH_BAD,
    SKIP,
}
