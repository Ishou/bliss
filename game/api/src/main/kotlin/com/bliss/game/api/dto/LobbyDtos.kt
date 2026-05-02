// Wire DTOs for the lobby REST surface. Pure: no domain imports, no Ktor.
// Field names mirror game/api/openapi.yaml verbatim (camelCase per ADR-0003 §6).
// Domain ↔ DTO translation lives in `mapper/LobbyResponseMapper.kt` so this
// file stays consumable by codegen tooling. The `dto package does not import
// domain types` Konsist rule (ApiArchitectureTest) enforces the boundary.
package com.bliss.game.api.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** `Lobby` schema. `game` is required + nullable per ADR-0003 §6 (absence != null). */
@Serializable
data class LobbyResponseDto(
    val id: String,
    val ownerSessionId: String,
    val players: List<PlayerDto>,
    val state: String,
    val gridConfig: GridConfigDto,
    val game: GameSessionDto?,
)

@Serializable
data class PlayerDto(
    val sessionId: String,
    val pseudonym: String,
    val joinedAt: String,
)

@Serializable
data class GridConfigDto(
    val width: Int,
    val height: Int,
)

/**
 * `GameSession` schema. `completedAt` is required + nullable on the wire.
 * `entries` lists every cell typed so far (server-authoritative, sorted by
 * row then column) so a refreshing client rehydrates the grid state from
 * this snapshot instead of receiving an empty grid. Cleared cells are
 * absent from the list.
 */
@Serializable
data class GameSessionDto(
    val puzzle: GamePuzzleDto,
    val entries: List<CellEntryDto>,
    val startedAt: String,
    val completedAt: String?,
)

/** `GamePuzzle` schema (mirrors `game/api/asyncapi.yaml`'s shape; keep in sync). */
@Serializable
data class GamePuzzleDto(
    val id: String,
    val title: String,
    val language: String,
    val width: Int,
    val height: Int,
    val cells: List<GameCellDto>,
    val clues: List<GameClueDto>,
    val createdAt: String,
)

/**
 * `GameCell` discriminated union (`kind` field). The 2-clue corner-cell idiom
 * is carried by [GameCellDto.Definition.clues] (1..2 entries) per PR #135.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface GameCellDto {
    val position: GamePositionDto

    @Serializable
    @SerialName("letter")
    data class Letter(
        override val position: GamePositionDto,
        // Required + nullable on the wire: null = blank, A-Z = pre-filled.
        val letter: String?,
    ) : GameCellDto

    @Serializable
    @SerialName("definition")
    data class Definition(
        override val position: GamePositionDto,
        val clues: List<GameDefinitionClueDto>,
    ) : GameCellDto

    @Serializable
    @SerialName("block")
    data class Block(
        override val position: GamePositionDto,
    ) : GameCellDto
}

@Serializable
data class GameDefinitionClueDto(
    val id: String,
    val text: String,
    val arrow: String,
)

@Serializable
data class GamePositionDto(
    val row: Int,
    val column: Int,
)

@Serializable
data class GameClueDto(
    val id: String,
    val direction: String,
    val start: GamePositionDto,
    val length: Int,
    val text: String,
)

@Serializable
data class CreateLobbyRequestDto(
    val ownerSessionId: String,
    val ownerPseudonym: String,
)
