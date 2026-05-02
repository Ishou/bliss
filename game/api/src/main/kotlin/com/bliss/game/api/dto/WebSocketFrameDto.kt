package com.bliss.game.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wire DTOs for the WebSocket channel `/v1/lobbies/{lobbyId}/ws`. Wire shapes
 * are authoritative in `game/api/asyncapi.yaml` (ADR-0003 §5); these
 * `@Serializable` types mirror that catalog. Per the API architecture test,
 * DTOs do NOT import `com.bliss.game.domain.*` or `io.ktor.*` — domain mapping
 * lives in `routes/WebSocketFrameMapper.kt`.
 *
 * The top-level `type` discriminator on every frame is encoded via
 * [JsonClassDiscriminator]. The `Error` variant intentionally renames its
 * RFC 7807 type-URI field to `errorType` to avoid colliding with the
 * discriminator (matches the AsyncAPI ErrorPayload field name).
 *
 * Embedded shapes referenced below (`PlayerDto`, `GridConfigDto`,
 * `GameSessionDto`, `GamePuzzleDto`, `GameCellDto`, `GameDefinitionClueDto`,
 * `GamePositionDto`, `GameClueDto`) live in `LobbyDtos.kt` (PR #137); both
 * the REST surface and this WebSocket surface deliberately consume the same
 * @Serializable shapes so a `Lobby` snapshot serialised on either path is
 * byte-identical. `CellEntryDto` is unique to the WebSocket surface.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class ClientToServerFrame {
    @Serializable
    @SerialName("joinLobby")
    data class JoinLobby(
        val sessionId: String,
        val pseudonym: String,
    ) : ClientToServerFrame()

    @Serializable
    @SerialName("renameSelf")
    data class RenameSelf(
        val newPseudonym: String,
    ) : ClientToServerFrame()

    @Serializable
    @SerialName("setGridConfig")
    data class SetGridConfig(
        val width: Int,
        val height: Int,
    ) : ClientToServerFrame()

    @Serializable
    @SerialName("startGame")
    object StartGame : ClientToServerFrame()

    @Serializable
    @SerialName("cellUpdate")
    data class CellUpdate(
        val row: Int,
        val column: Int,
        val letter: String?,
    ) : ClientToServerFrame()

    @Serializable
    @SerialName("leaveLobby")
    object LeaveLobby : ClientToServerFrame()
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class ServerToClientFrame {
    @Serializable
    @SerialName("lobbyState")
    data class LobbyState(
        val players: List<PlayerDto>,
        val ownerSessionId: String,
        val state: String,
        val gridConfig: GridConfigDto,
        val game: GameSessionDto? = null,
    ) : ServerToClientFrame()

    @Serializable
    @SerialName("playerJoined")
    data class PlayerJoined(
        val sessionId: String,
        val pseudonym: String,
        val joinedAt: String,
    ) : ServerToClientFrame()

    @Serializable
    @SerialName("playerLeft")
    data class PlayerLeft(
        val sessionId: String,
    ) : ServerToClientFrame()

    @Serializable
    @SerialName("playerRenamed")
    data class PlayerRenamed(
        val sessionId: String,
        val newPseudonym: String,
    ) : ServerToClientFrame()

    @Serializable
    @SerialName("gameStarted")
    data class GameStarted(
        val puzzle: GamePuzzleDto,
        val startedAt: String,
    ) : ServerToClientFrame()

    @Serializable
    @SerialName("cellUpdated")
    data class CellUpdated(
        val sessionId: String,
        val row: Int,
        val column: Int,
        val letter: String?,
        val writtenAt: String,
    ) : ServerToClientFrame()

    @Serializable
    @SerialName("gameSolved")
    data class GameSolved(
        val durationMs: Long,
        val finalEntries: List<CellEntryDto>,
    ) : ServerToClientFrame()

    /**
     * RFC 7807-shaped error frame. Wire field is `errorType` (NOT `type`) so
     * the kotlinx-serialization discriminator does not collide with the URI.
     */
    @Serializable
    @SerialName("error")
    data class Error(
        val errorType: String,
        val title: String,
        val detail: String? = null,
        val status: Int? = null,
    ) : ServerToClientFrame()
}

/**
 * Server-side cell entry projection used in `gameSolved`'s final-state digest.
 * Unique to the WebSocket surface — REST never emits it (puzzle answers are
 * domain-private until the game is solved).
 */
@Serializable
data class CellEntryDto(
    val row: Int,
    val column: Int,
    val letter: String,
)
