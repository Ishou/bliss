package com.bliss.grid.api.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * `Puzzle` schema from `grid/api/openapi.yaml`. Pure wire types; the API
 * layer owns serialization, the mapper package owns the domain → DTO
 * translation (ADR-0003 §4).
 */
@Serializable
data class PuzzleResponse(
    val id: String,
    val title: String,
    val language: String,
    val width: Int,
    val height: Int,
    val cells: List<CellDto>,
    val clues: List<ClueDto>,
    val hintsAllowed: Int,
    val createdAt: String,
    val difficulty: String? = null,
    val gridNumber: Int? = null,
)

/**
 * `Cell` discriminated union (`kind` field). Matches the OpenAPI spec's
 * `oneOf` with `discriminator: { propertyName: kind, mapping: { ... } }`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface CellDto {
    val position: PositionDto

    @Serializable
    data class PositionDto(
        val row: Int,
        val column: Int,
    )
}

@Serializable
@SerialName("letter")
data class LetterCellDto(
    override val position: CellDto.PositionDto,
) : CellDto

@Serializable
@SerialName("definition")
data class DefinitionCellDto(
    override val position: CellDto.PositionDto,
    val clueId: String,
    val text: String,
    val arrow: String,
) : CellDto

@Serializable
@SerialName("block")
data class BlockCellDto(
    override val position: CellDto.PositionDto,
) : CellDto

@Serializable
data class ClueDto(
    val id: String,
    val direction: String,
    val start: CellDto.PositionDto,
    val length: Int,
    val text: String,
)
