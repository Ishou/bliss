// Wire DTOs that mirror grid/api/openapi.yaml#/components/schemas/Puzzle verbatim.
// Per ADR-0001 §1, this module never imports from com.bliss.grid.* — game/'s view of
// the upstream wire shape lives entirely here. ADR-0018 §1 covers the duplication.
// Field names match the OpenAPI spec exactly (camelCase per ADR-0003 §6).
package com.bliss.game.infrastructure

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
internal data class PuzzleResponseDto(
    val id: String,
    val title: String,
    val language: String,
    val width: Int,
    val height: Int,
    val cells: List<CellDto>,
    val clues: List<ClueDto>,
    val createdAt: String,
)

@Serializable
internal data class PositionDto(
    val row: Int,
    val column: Int,
)

// Discriminated union on `kind`. The OpenAPI spec uses lowercase variants
// (`letter` / `definition` / `block`); kotlinx.serialization picks them from @SerialName.
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
internal sealed class CellDto {
    abstract val position: PositionDto

    @Serializable
    @SerialName("letter")
    data class Letter(
        override val position: PositionDto,
        // null = explicit blank in canonical solution; absent = no constraint declared.
        // OpenAPI marks `letter` as nullable + non-required, but in practice grid emits
        // either an uppercase A-Z or omits the field for solver-only cells.
        val letter: String? = null,
    ) : CellDto()

    @Serializable
    @SerialName("definition")
    data class Definition(
        override val position: PositionDto,
        val clueId: String,
        val text: String,
        val arrow: String,
    ) : CellDto()

    @Serializable
    @SerialName("block")
    data class Block(
        override val position: PositionDto,
    ) : CellDto()
}

@Serializable
internal data class ClueDto(
    val id: String,
    val direction: String,
    val start: PositionDto,
    val length: Int,
    val text: String,
)
