package com.bliss.game.api.routes

import com.bliss.game.api.PresencePosition
import com.bliss.game.api.dto.CellEntryDto
import com.bliss.game.api.dto.GameCellDto
import com.bliss.game.api.dto.GameClueDto
import com.bliss.game.api.dto.GameDefinitionClueDto
import com.bliss.game.api.dto.GamePositionDto
import com.bliss.game.api.dto.GamePuzzleDto
import com.bliss.game.api.dto.GameSessionDto
import com.bliss.game.api.dto.GridConfigDto
import com.bliss.game.api.dto.PlayerDto
import com.bliss.game.api.dto.PresenceEntryDto
import com.bliss.game.api.dto.ServerToClientFrame
import com.bliss.game.application.ports.LobbyEvent
import com.bliss.game.application.usecases.UseCaseError
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
import java.time.Instant

/**
 * Maps domain types and [LobbyEvent]s to wire DTOs declared in
 * [com.bliss.game.api.dto.WebSocketFrameDto]. Lives outside the `dto/`
 * package because the API architecture test forbids domain imports inside
 * DTO sources.
 */

internal fun Lobby.toLobbyStateFrame(presence: Map<String, PresencePosition> = emptyMap()): ServerToClientFrame.LobbyState =
    ServerToClientFrame.LobbyState(
        players = players.values.sortedBy { it.joinedAt }.map { it.toDto() },
        ownerSessionId = ownerSessionId.value,
        state = state.name,
        gridConfig = gridConfig.toDto(),
        // Presence is ephemeral and only meaningful while IN_PROGRESS — outside
        // that we drop it on the floor (matches asyncapi `GameSession.presence`
        // which is "absent or empty when state is WAITING/COMPLETED").
        game = game?.toDto(if (state == LobbyLifecycleState.IN_PROGRESS) presence else emptyMap()),
    )

internal fun LobbyEvent.toFrameOrNull(): ServerToClientFrame? =
    when (this) {
        is LobbyEvent.PlayerJoined ->
            ServerToClientFrame.PlayerJoined(
                sessionId = player.sessionId.value,
                pseudonym = player.pseudonym.value,
                joinedAt = player.joinedAt.toIsoString(),
            )
        is LobbyEvent.PlayerLeft ->
            ServerToClientFrame.PlayerLeft(sessionId = sessionId.value)
        is LobbyEvent.PlayerRenamed ->
            ServerToClientFrame.PlayerRenamed(
                sessionId = sessionId.value,
                newPseudonym = pseudonym.value,
            )
        is LobbyEvent.GameStarted ->
            ServerToClientFrame.GameStarted(
                puzzle = session.puzzle.toDto(),
                startedAt = session.startedAt.toIsoString(),
            )
        is LobbyEvent.CellUpdated ->
            ServerToClientFrame.CellUpdated(
                sessionId = sessionId.value,
                row = position.row,
                column = position.column,
                letter = letter?.value?.toString(),
                writtenAt = writtenAt.toIsoString(),
            )
        is LobbyEvent.GameSolved ->
            ServerToClientFrame.GameSolved(
                durationMs = durationMs,
                finalEntries =
                    finalEntries.entries
                        .sortedWith(compareBy({ it.key.row }, { it.key.column }))
                        .map { (pos, entry) -> entry.toDto(pos) },
            )
        // GridConfigChanged: AsyncAPI has no dedicated wire frame; the route
        // re-broadcasts the full LobbyState snapshot instead (see LobbyEvent.kt).
        is LobbyEvent.GridConfigChanged -> null
        // LobbyClosed: the server closes the socket; nothing to broadcast.
        is LobbyEvent.LobbyClosed -> null
        is LobbyEvent.Typing ->
            ServerToClientFrame.Typing(
                sessionId = sessionId.value,
                typing = typing,
            )
        is LobbyEvent.Idle ->
            ServerToClientFrame.Idle(
                sessionId = sessionId.value,
                idle = idle,
            )
        is LobbyEvent.ConnectionLost ->
            ServerToClientFrame.ConnectionLost(sessionId = sessionId.value)
        is LobbyEvent.CursorBumped ->
            ServerToClientFrame.CursorBumped(
                sessionId = sessionId.value,
                row = position.row,
                column = position.column,
                direction = direction.toWire(),
            )
    }

internal fun UseCaseError.toErrorFrame(): ServerToClientFrame.Error =
    when (this) {
        UseCaseError.LobbyNotFound ->
            ServerToClientFrame.Error(
                errorType = "https://bliss.example/errors/lobby-not-found",
                title = "Salon introuvable",
                status = 404,
            )
        UseCaseError.LobbyFull ->
            ServerToClientFrame.Error(
                errorType = "https://bliss.example/errors/lobby-full",
                title = "Salon complet",
                status = 409,
            )
        UseCaseError.NotOwner ->
            ServerToClientFrame.Error(
                errorType = "https://bliss.example/errors/not-owner",
                title = "Opération réservée au propriétaire",
                status = 403,
            )
        UseCaseError.InvalidState ->
            ServerToClientFrame.Error(
                errorType = "https://bliss.example/errors/invalid-state",
                title = "État de salon invalide pour cette opération",
                status = 409,
            )
        UseCaseError.PlayerNotInLobby ->
            ServerToClientFrame.Error(
                errorType = "https://bliss.example/errors/player-not-in-lobby",
                title = "Vous n'êtes pas membre de ce salon",
                status = 403,
            )
        is UseCaseError.InvalidArgument ->
            ServerToClientFrame.Error(
                errorType = "https://bliss.example/errors/invalid-argument",
                title = "Argument invalide",
                detail = detail,
                status = 400,
            )
    }

internal fun protocolErrorFrame(
    title: String,
    detail: String,
    status: Int = 400,
): ServerToClientFrame.Error =
    ServerToClientFrame.Error(
        errorType = "https://bliss.example/errors/protocol",
        title = title,
        detail = detail,
        status = status,
    )

private fun Player.toDto(): PlayerDto =
    PlayerDto(
        sessionId = sessionId.value,
        pseudonym = pseudonym.value,
        joinedAt = joinedAt.toIsoString(),
    )

private fun GridConfig.toDto(): GridConfigDto = GridConfigDto(width = width, height = height)

private fun GameSession.toDto(presence: Map<String, PresencePosition>): GameSessionDto =
    GameSessionDto(
        puzzle = puzzle.toDto(),
        // Stable order = sort by row then column so two structurally-equal
        // sessions always serialize byte-identically. The domain `entries`
        // map is unordered (Map<Position, CellEntry>); the wire is an
        // ordered array so the frontend can render deterministically.
        entries =
            entries.entries
                .sortedWith(compareBy({ it.key.row }, { it.key.column }))
                .map { (pos, entry) -> entry.toDto(pos) },
        startedAt = startedAt.toIsoString(),
        completedAt = completedAt?.toIsoString(),
        // Presence is unordered in [SessionManager]'s map; sort by sessionId
        // for stable JSON ordering, mirroring the entries sort above.
        presence =
            presence.entries
                .sortedBy { it.key }
                .map { (sid, pos) -> PresenceEntryDto(sid, pos.row, pos.column, pos.direction) },
    )

private fun GamePuzzle.toDto(): GamePuzzleDto =
    GamePuzzleDto(
        id = id.toString(),
        title = title,
        language = language,
        width = width,
        height = height,
        cells = cells.map { it.toDto() },
        clues = clues.map { it.toDto() },
        createdAt = createdAt.toIsoString(),
    )

private fun GameCell.toDto(): GameCellDto =
    when (this) {
        // Wire `letter` is the BLANK / pre-filled-input slot, NOT the canonical
        // answer. Puzzle answers are domain-private until `gameSolved` (see the
        // sibling note for `CellEntryDto` in `WebSocketFrameDto.kt`); leaking
        // them via `gameStarted.puzzle.cells[*].letter` would render the grid
        // pre-solved on every client. Player input is broadcast separately via
        // `cellUpdated` events keyed by sessionId.
        is LetterCell ->
            GameCellDto.Letter(
                position = position.toDto(),
                letter = null,
            )
        is DefinitionCell ->
            GameCellDto.Definition(
                position = position.toDto(),
                clues = clues.map { it.toDto() },
            )
        is BlockCell -> GameCellDto.Block(position = position.toDto())
    }

private fun Position.toDto(): GamePositionDto = GamePositionDto(row = row, column = column)

private fun GameDefinitionClue.toDto(): GameDefinitionClueDto =
    GameDefinitionClueDto(
        id = id.toString(),
        text = text,
        arrow = arrow.toWire(),
    )

private fun GameClue.toDto(): GameClueDto =
    GameClueDto(
        id = id.toString(),
        direction = direction.toWire(),
        start = start.toDto(),
        length = length,
        text = text,
    )

private fun CellEntry.toDto(position: Position): CellEntryDto =
    CellEntryDto(
        sessionId = sessionId.value,
        row = position.row,
        column = position.column,
        letter = letter.value.toString(),
        writtenAt = writtenAt.toIsoString(),
    )

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

private fun Instant.toIsoString(): String = this.toString()
