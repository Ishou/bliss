// Domain → wire translation for the lobby REST surface. Pure: no IO, no clock.
// DTOs never escape the api layer; the inbound-only CreateLobbyRequestDto is
// unwrapped to domain types in the route itself.
//
// Lives outside `com.bliss.game.api.dto` because it imports
// `com.bliss.game.domain.*`, which the `dto package does not import domain
// types` Konsist rule (ApiArchitectureTest) forbids inside the dto package.
package com.bliss.game.api.mapper

import com.bliss.game.api.PresencePosition
import com.bliss.game.api.dto.CellEntryDto
import com.bliss.game.api.dto.GameCellDto
import com.bliss.game.api.dto.GameClueDto
import com.bliss.game.api.dto.GameDefinitionClueDto
import com.bliss.game.api.dto.GamePositionDto
import com.bliss.game.api.dto.GamePuzzleDto
import com.bliss.game.api.dto.GameSessionDto
import com.bliss.game.api.dto.GridConfigDto
import com.bliss.game.api.dto.LobbyResponseDto
import com.bliss.game.api.dto.PlayerDto
import com.bliss.game.api.dto.PresenceEntryDto
import com.bliss.game.domain.BlockCell
import com.bliss.game.domain.CellEntry
import com.bliss.game.domain.DefinitionCell
import com.bliss.game.domain.GameArrow
import com.bliss.game.domain.GameCell
import com.bliss.game.domain.GameClue
import com.bliss.game.domain.GameClueDirection
import com.bliss.game.domain.GameDefinitionClue
import com.bliss.game.domain.GamePuzzle
import com.bliss.game.domain.GameSession
import com.bliss.game.domain.GridConfig
import com.bliss.game.domain.LetterCell
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyLifecycleState
import com.bliss.game.domain.Player
import com.bliss.game.domain.Position
import java.time.format.DateTimeFormatter

/**
 * Map a [Lobby] aggregate to its wire shape. The `players` map is serialized
 * as an array sorted by [Player.joinedAt] then sessionId so two structurally-
 * equal lobbies always produce identical JSON; JSON object keys are unordered,
 * an ordered array gives the frontend stable rendering.
 */
fun Lobby.toResponseDto(presence: Map<String, PresencePosition> = emptyMap()): LobbyResponseDto =
    LobbyResponseDto(
        id = id.value,
        ownerSessionId = ownerSessionId.value,
        players =
            players.values
                .sortedWith(compareBy({ it.joinedAt }, { it.sessionId.value }))
                .map { it.toDto() },
        state = state.name,
        gridConfig = gridConfig.toDto(),
        // Presence is meaningful only while IN_PROGRESS — outside that we drop
        // the map (matches openapi `GameSession.presence` which is "absent or
        // empty when state is WAITING/COMPLETED").
        game = game?.toDto(if (state == LobbyLifecycleState.IN_PROGRESS) presence else emptyMap()),
        code = code.value,
    )

private fun Player.toDto() = PlayerDto(sessionId.value, pseudonym.value, ISO.format(joinedAt))

private fun GridConfig.toDto() = GridConfigDto(width, height)

// Stable ordering: sort by row then column so two structurally-equal sessions
// always produce identical JSON (the domain `entries` map is unordered; the
// wire is an ordered array).
private fun GameSession.toDto(presence: Map<String, PresencePosition>) =
    GameSessionDto(
        puzzle = puzzle.toDto(),
        entries =
            entries.entries
                .sortedWith(compareBy({ it.key.row }, { it.key.column }))
                .map { (pos, entry) -> entry.toDto(pos) },
        lockedPositions =
            lockedPositions
                .sortedWith(compareBy({ it.row }, { it.column }))
                .map { GamePositionDto(row = it.row, column = it.column) },
        startedAt = ISO.format(startedAt),
        completedAt = completedAt?.let(ISO::format),
        // Sort by sessionId for deterministic JSON, mirroring `entries` above.
        presence =
            presence.entries
                .sortedBy { it.key }
                .map { (sid, pos) -> PresenceEntryDto(sid, pos.row, pos.column, pos.direction) },
    )

private fun CellEntry.toDto(position: Position) =
    CellEntryDto(
        sessionId = sessionId.value,
        row = position.row,
        column = position.column,
        letter = letter.value.toString(),
        writtenAt = ISO.format(writtenAt),
    )

private fun GamePuzzle.toDto() =
    GamePuzzleDto(
        id = id.toString(),
        title = title,
        language = language,
        width = width,
        height = height,
        cells = cells.map { it.toDto() },
        clues = clues.map { it.toDto() },
        createdAt = ISO.format(createdAt),
    )

private fun GameCell.toDto(): GameCellDto =
    when (this) {
        // Wire `letter` is the BLANK / pre-filled-input slot, NOT the canonical
        // answer. Puzzle answers are domain-private until `gameSolved` (see the
        // ADR-0018 echo in `WebSocketFrameDto.kt` for `finalEntries`); leaking
        // them in `gameStarted.puzzle.cells[*].letter` would render the grid
        // pre-solved on the client. Player input flows back via `cellUpdated`
        // events keyed by sessionId, never via this projection.
        is LetterCell -> GameCellDto.Letter(position.toDto(), letter = null)
        is DefinitionCell -> GameCellDto.Definition(position.toDto(), clues.map { it.toDto() })
        is BlockCell -> GameCellDto.Block(position.toDto())
    }

private fun GameDefinitionClue.toDto() = GameDefinitionClueDto(id.toString(), text, arrow.toWire())

private fun GameClue.toDto() = GameClueDto(id.toString(), direction.toWire(), start.toDto(), length, text)

private fun Position.toDto() = GamePositionDto(row, column)

// Domain enums are SHOUT_SNAKE; wire enums are kebab-case. Mirrors
// game/api/asyncapi.yaml's GameArrow / GameClueDirection enums.
private fun GameArrow.toWire(): String =
    when (this) {
        GameArrow.RIGHT -> "right"
        GameArrow.DOWN -> "down"
        GameArrow.DOWN_RIGHT -> "down-right"
        GameArrow.RIGHT_DOWN -> "right-down"
    }

private fun GameClueDirection.toWire(): String =
    when (this) {
        GameClueDirection.ACROSS -> "across"
        GameClueDirection.DOWN -> "down"
    }

private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
