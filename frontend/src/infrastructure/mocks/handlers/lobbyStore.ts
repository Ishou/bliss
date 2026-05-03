// Module-level state shared between the MSW REST handlers and the WS
// link handler. Lives only inside the preview bundle (per ADR-0007 §5
// the entire `mocks/` tree is tree-shaken in production); state survives
// page navigations but resets on full reload — exactly the contract a
// reviewer needs for "click Create, land on /lobby/:id, see WaitingRoom".
//
// The store is in-memory and intentionally never garbage-collects: a
// preview tab opens at most a handful of lobbies during a review pass,
// and any leak vanishes on reload. Persistence (sessionStorage / IDB)
// would be over-engineered for a 30-minute review.
//
// Why a separate file: REST creates and reads `Lobby` records; the WS
// handler reads the same record (lookup by `lobbyId`) on connect to
// emit a `lobbyState` snapshot, mutates it on `setGridConfig` /
// `startGame`, then drives the bot loop off the same puzzle. Co-locating
// the store keeps both handlers honest about the single source of truth.

import type { components } from '@/infrastructure/api/game/types';

export type Lobby = components['schemas']['Lobby'];
export type Player = components['schemas']['Player'];
export type GamePuzzle = components['schemas']['GamePuzzle'];
export type GameSession = components['schemas']['GameSession'];

const lobbies = new Map<string, Lobby>();

// Stable session id for the simulated bot opponent. Reused across every
// preview lobby so any test that pins on it can reference the constant.
// UUID v7 prefix `0190e3a4-...` mirrors the spec example and looks
// plausible to a reviewer eyeballing the WS frames in DevTools.
export const BOT_SESSION_ID = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c';
export const BOT_PSEUDONYM = 'Bot 🤖';

// 8-char base58 alphabet per ADR-0020 (no `0`, `O`, `I`, `l`).
const BASE58 = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';

export function generateLobbyId(): string {
  let id = '';
  for (let i = 0; i < 8; i++) {
    id += BASE58.charAt(Math.floor(Math.random() * BASE58.length));
  }
  return id;
}

export function putLobby(lobby: Lobby): void {
  lobbies.set(lobby.id, lobby);
}

export function getLobby(lobbyId: string): Lobby | undefined {
  return lobbies.get(lobbyId);
}

export function updateLobby(
  lobbyId: string,
  mutator: (current: Lobby) => Lobby,
): Lobby | undefined {
  const current = lobbies.get(lobbyId);
  if (!current) return undefined;
  const next = mutator(current);
  lobbies.set(lobbyId, next);
  return next;
}

// Test-only reset hook. Production builds tree-shake this whole module
// out so the export is harmless.
export function __resetLobbyStore(): void {
  lobbies.clear();
}

// Hand-crafted small mots-fléchés puzzle for preview demos. 5x5 keeps it
// tiny enough that a reviewer can fill it in under a minute, and the
// shape matches the smallest GridConfig the WaitingRoom picker offers.
//
// Layout:
//   D L L L L      row 0: one definition cell, four letters
//   L L L L L      row 1: five letters
//   L L B L L      row 2: four letters with a block in the middle
//   L L L L L      row 3: five letters
//   L L L L L      row 4: five letters
export function buildMockPuzzle(): GamePuzzle {
  // Use a deterministic id so the mock is recognizable in DevTools.
  const id = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6e';
  const cells: GamePuzzle['cells'] = [];
  for (let row = 0; row < 5; row++) {
    for (let column = 0; column < 5; column++) {
      if (row === 0 && column === 0) {
        cells.push({
          kind: 'definition',
          position: { row, column },
          clues: [{
            id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6f',
            text: 'Démo',
            arrow: 'right',
          }],
        });
      } else if (row === 2 && column === 2) {
        cells.push({ kind: 'block', position: { row, column } });
      } else {
        cells.push({ kind: 'letter', position: { row, column }, letter: null });
      }
    }
  }
  return {
    id,
    title: 'Aperçu multijoueur',
    language: 'fr',
    width: 5,
    height: 5,
    cells,
    clues: [
      {
        id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6f',
        direction: 'across',
        start: { row: 0, column: 1 },
        length: 4,
        text: 'Démo',
      },
    ],
    createdAt: new Date().toISOString(),
  };
}

// Returns a fresh, never-completed `GameSession` wrapping the mock puzzle.
// Used by the WS handler when the owner sends `startGame` so the lobby
// state mutation and the broadcast frame stay in lockstep.
export function buildGameSession(): GameSession {
  return {
    puzzle: buildMockPuzzle(),
    startedAt: new Date().toISOString(),
    completedAt: null,
    entries: [],
  };
}
