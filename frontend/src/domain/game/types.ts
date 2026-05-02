// Frontend mirror of the game/ AsyncAPI shapes (game/api/asyncapi.yaml).
// Hand-typed because no AsyncAPI codegen pipeline exists yet (cf. ADR-0019);
// every field name and shape matches the wire verbatim. Pure types — no
// runtime, no React, no fetch — per ADR-0002 §7 hexagonal layering.
//
// Disambiguation note: this domain owns names like `Position`, `Cell`,
// `Puzzle` already used by the puzzle/ context. We do NOT prefix them with
// `Game` here — the package path (`@/domain/game`) disambiguates. The
// AsyncAPI `Game*` schema names reflect the wire-level need to avoid
// collisions with grid/'s OpenAPI components; that constraint does not
// apply on the TS side.

// ----- Branded ID types (TS equivalent of Kotlin value classes) -----
// Each is a `string` at runtime but nominally distinct at compile time so
// callers cannot pass a `LobbyId` where a `SessionId` is required.
export type LobbyId = string & { readonly __brand: 'LobbyId' };
export type SessionId = string & { readonly __brand: 'SessionId' };
export type Pseudonym = string & { readonly __brand: 'Pseudonym' };
export type Letter = string & { readonly __brand: 'Letter' };

// ISO-8601 instant with timezone offset (ADR-0003 §6). Plain `string` on
// the wire; kept aliased for documentation.
export type Instant = string;

// ----- Lifecycle enum -----
export type LobbyLifecycleState = 'WAITING' | 'IN_PROGRESS' | 'COMPLETED';

// ----- Core data shapes -----
export interface Position {
  readonly row: number;
  readonly column: number;
}

export interface GridConfig {
  readonly width: number;
  readonly height: number;
}

export interface Player {
  readonly sessionId: SessionId;
  readonly pseudonym: Pseudonym;
  readonly joinedAt: Instant;
}

// A single placed letter in the canonical solution entries list. `letter`
// is always non-null here — see AsyncAPI `CellEntry`.
export interface CellEntry {
  readonly row: number;
  readonly column: number;
  readonly letter: Letter;
}

// ----- Puzzle projection (game-context) -----
export type GameArrowDirection = 'right' | 'down' | 'down-right' | 'right-down';
export type GameClueDirection = 'across' | 'down';

// `letter` is the placeholder / pre-fill slot on the wire. Per
// game/api/asyncapi.yaml `GameLetterCell`, the server ALWAYS emits `null`
// here in v1: the canonical solution is domain-private until `gameSolved`
// (otherwise the grid would render pre-solved on every client). Player
// input is broadcast separately via `cellUpdated` events. The field is
// kept in the type for forward-compat with a future pre-filled / replay
// use case but the route-local adapter MUST NOT route it into the UI's
// `entry` field — see `lobby.$lobbyId.tsx` for the conversion.
export interface GameLetterCell {
  readonly kind: 'letter';
  readonly position: Position;
  readonly letter: Letter | null;
}

// 1 or 2 clues per cell — matches `GameDefinitionCell` in
// `game/api/asyncapi.yaml`. The 2-clue case is the mots-fléchés
// corner-cell idiom (an across clue and a down clue stacked at the
// same position). Mirrors the backend domain shape in
// `game/domain/.../GameCell.kt`.
export interface GameDefinitionCell {
  readonly kind: 'definition';
  readonly position: Position;
  readonly clues: readonly GameDefinitionCellClue[];
}

export interface GameDefinitionCellClue {
  readonly id: string;
  readonly text: string;
  readonly arrow: GameArrowDirection;
}

export interface GameBlockCell {
  readonly kind: 'block';
  readonly position: Position;
}

export type GameCell = GameLetterCell | GameDefinitionCell | GameBlockCell;

export interface GameDefinitionClue {
  readonly id: string;
  readonly direction: GameClueDirection;
  readonly start: Position;
  readonly length: number;
  readonly text: string;
}

export interface GamePuzzle {
  readonly id: string;
  readonly title: string;
  readonly language: string;
  readonly width: number;
  readonly height: number;
  readonly cells: readonly GameCell[];
  readonly clues: readonly GameDefinitionClue[];
  readonly createdAt: Instant;
}

// Active game embedded in `lobbyState` while IN_PROGRESS.
export interface GameSession {
  readonly puzzle: GamePuzzle;
  readonly startedAt: Instant;
  readonly completedAt: Instant | null;
}

export interface Lobby {
  readonly players: readonly Player[];
  readonly ownerSessionId: SessionId;
  readonly state: LobbyLifecycleState;
  readonly gridConfig: GridConfig;
  readonly game: GameSession | null;
}
