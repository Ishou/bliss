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
        // Six-char Crockford join code (ADR-0027). Required for new joiners;
        // optional / ignored for already-joined sessions on the reconnect
        // path (server bypasses the check when sessionId is already a member).
        val code: String? = null,
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

    /**
     * Pure presence signal — peers render the sender's cursor and active-word
     * tint. Carries zero domain meaning; not persisted. All three position
     * fields are nullable: `null` means "no cell focused" (e.g. user clicked
     * off the grid). `direction` mirrors the wire `GameClueDirection`
     * (`across` | `down`); kept as a raw String here so this DTO file stays
     * free of domain imports (enforced by `ApiArchitectureTest`).
     */
    @Serializable
    @SerialName("cellFocus")
    data class CellFocus(
        val row: Int?,
        val column: Int?,
        val direction: String?,
    ) : ClientToServerFrame()

    @Serializable
    @SerialName("leaveLobby")
    object LeaveLobby : ClientToServerFrame()

    /** Owner-only join-code rotation (ADR-0029). No body; owner identity is implicit from the prior `joinLobby` binding. */
    @Serializable
    @SerialName("rotateCode")
    object RotateCode : ClientToServerFrame()
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
        // Always present — the server mints a code at create-time. Future
        // server-side mutations (rotation, future renames) propagate via
        // the same snapshot path as every other lobby field.
        val code: String,
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

    /**
     * Server broadcast of a peer's `cellFocus`. Ephemeral — carries no
     * domain meaning, is not persisted, and is dropped on lobby
     * WAITING/COMPLETED transitions. Mirrors `PresenceUpdatedPayload` in
     * `game/api/asyncapi.yaml`. `direction` is the wire enum value
     * (`across` | `down`) kept as a raw String for the same domain-import
     * reason as `ClientToServerFrame.CellFocus`.
     */
    @Serializable
    @SerialName("presenceUpdated")
    data class PresenceUpdated(
        val sessionId: String,
        val row: Int?,
        val column: Int?,
        val direction: String?,
    ) : ServerToClientFrame()

    /** Boolean typing-edge for a peer; [typing]=true on keystroke, false when the trailing-edge gap expires. */
    @Serializable
    @SerialName("typing")
    data class Typing(
        val sessionId: String,
        val typing: Boolean,
    ) : ServerToClientFrame()

    /** Idle-edge for a peer; independent of [ConnectionLost]. */
    @Serializable
    @SerialName("idle")
    data class Idle(
        val sessionId: String,
        val idle: Boolean,
    ) : ServerToClientFrame()

    /** Peer's WebSocket closed; slot held during grace window. Distinct from [PlayerLeft] — clients grey-out, don't remove. */
    @Serializable
    @SerialName("connectionLost")
    data class ConnectionLost(
        val sessionId: String,
    ) : ServerToClientFrame()

    /** Server-authoritative cursor bump. direction kept as String to avoid domain imports in this DTO file. */
    @Serializable
    @SerialName("cursorBumped")
    data class CursorBumped(
        val sessionId: String,
        val row: Int,
        val column: Int,
        val direction: String,
    ) : ServerToClientFrame()

    /**
     * Server broadcast: every cell in [positions] is now locked (its
     * containing word was just validated correct). Mirrors `WordLockedPayload`
     * in `game/api/asyncapi.yaml`. A crossing fill that closes two words at
     * once produces a single frame whose `positions` is the union.
     */
    @Serializable
    @SerialName("wordLocked")
    data class WordLocked(
        val positions: List<GamePositionDto>,
        val lockedAt: String,
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
 * Server-side cell entry projection. Used by both the WebSocket surface
 * (`gameSolved.finalEntries` end-of-game digest, and `lobbyState.game.entries`
 * mid-game snapshot for reconnecting / late-joining clients) and the REST
 * surface (`Lobby.game.entries` so a client refreshing the page rehydrates
 * the grid before opening the WebSocket). `letter` is always non-null —
 * cleared cells are absent from the list. `sessionId` and `writtenAt`
 * mirror the per-cell `cellUpdated` event so a future per-author UI tint
 * can colour each entry.
 */
@Serializable
data class CellEntryDto(
    val sessionId: String,
    val row: Int,
    val column: Int,
    val letter: String,
    val writtenAt: String,
)

/**
 * Snapshot projection of one currently-connected player's cursor. Carried by
 * `lobbyState.game.presence` and `Lobby.game.presence` so a refreshing /
 * late-joining client sees peer cursors immediately. All three position
 * fields are nullable: `null` means the player has no cell focused.
 * Absence from the list means the player is not connected; per ADR-0003 §6,
 * absence and `null` are distinct. Mirrors `PresenceEntry` in both
 * `game/api/asyncapi.yaml` and `game/api/openapi.yaml`.
 */
@Serializable
data class PresenceEntryDto(
    val sessionId: String,
    val row: Int?,
    val column: Int?,
    val direction: String?,
)
