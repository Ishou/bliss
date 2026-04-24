package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Position

internal data class CandidatePlacement(
    val cluePosition: Position,
    val direction: Direction,
    val length: Int,
)
