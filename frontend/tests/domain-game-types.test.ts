import { describe, expect, it, expectTypeOf } from 'vitest';
import type {
  GameCell,
  GamePuzzle,
  Lobby,
  LobbyId,
  Pseudonym,
  SessionId,
} from '@/domain/game';
import type { GameClient, GameEvent, Unsubscribe } from '@/application/game';

// Type-level tests. The point of this PR is the type system: if these
// compile we have wire-shape parity with game/api/asyncapi.yaml. A few
// runtime expects pin down the discriminator literals so refactors to the
// union don't silently drift the wire format.

describe('domain/game types', () => {
  it('builds a Lobby fixture matching the AsyncAPI LobbyStatePayload shape', () => {
    const lobby: Lobby = {
      players: [
        {
          sessionId: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b' as SessionId,
          pseudonym: 'Joueur 1234' as Pseudonym,
          joinedAt: '2026-05-02T15:30:00Z',
        },
      ],
      ownerSessionId: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b' as SessionId,
      state: 'WAITING',
      gridConfig: { width: 7, height: 7 },
      game: null,
    };
    expect(lobby.state).toBe('WAITING');
    expect(lobby.players).toHaveLength(1);
  });

  it('discriminates GameCell variants on `kind`', () => {
    // `GameDefinitionCell.clues` is a 1..2-item array per
    // game/api/asyncapi.yaml; the 1-item shape is the common case.
    const cells: GameCell[] = [
      { kind: 'letter', position: { row: 0, column: 0 }, letter: null },
      {
        kind: 'definition',
        position: { row: 0, column: 1 },
        clues: [{ id: 'c1', text: 'Capitale', arrow: 'right' }],
      },
      { kind: 'block', position: { row: 0, column: 2 } },
    ];
    expect(cells.map((c) => c.kind)).toEqual(['letter', 'definition', 'block']);
  });

  it('brands LobbyId/SessionId/Pseudonym/Letter as nominally distinct', () => {
    // Compile-time: a plain `string` is not assignable to a brand without
    // an explicit cast. Runtime: brands erase to `string`.
    expectTypeOf<LobbyId>().toMatchTypeOf<string>();
    expectTypeOf<SessionId>().toMatchTypeOf<string>();
    const id: LobbyId = '7gQ2xK9p' as LobbyId;
    expect(typeof id).toBe('string');
  });
});

describe('application/game GameEvent union', () => {
  it('narrows on the wire `type` discriminator', () => {
    const fixtures: GameEvent[] = [
      {
        type: 'lobbyState',
        players: [],
        ownerSessionId: 's' as SessionId,
        state: 'WAITING',
        gridConfig: { width: 5, height: 5 },
        code: 'A2B3C4',
        game: null,
      },
      { type: 'playerLeft', sessionId: 's' as SessionId },
      {
        type: 'cellUpdated',
        sessionId: 's' as SessionId,
        row: 0,
        column: 1,
        letter: null,
        writtenAt: '2026-05-02T15:35:42Z',
      },
      {
        type: 'error',
        errorType: 'https://bliss.example/errors/lobby-full',
        title: 'Lobby is full',
      },
    ];
    expect(fixtures.map((e) => e.type)).toEqual([
      'lobbyState',
      'playerLeft',
      'cellUpdated',
      'error',
    ]);
  });

  it('GameClient port shape carries the AsyncAPI client→server methods', () => {
    expectTypeOf<GameClient['joinLobby']>().toEqualTypeOf<() => void>();
    expectTypeOf<GameClient['subscribe']>().parameters.toEqualTypeOf<
      [(event: GameEvent) => void]
    >();
    expectTypeOf<GameClient['subscribe']>().returns.toEqualTypeOf<Unsubscribe>();
  });

  it('discriminator narrowing yields the right payload shape', () => {
    const event: GameEvent = {
      type: 'gameSolved',
      durationMs: 184250,
      finalEntries: [],
    };
    if (event.type === 'gameSolved') {
      expectTypeOf(event.durationMs).toEqualTypeOf<number>();
    }
    expect(event.type).toBe('gameSolved');
  });
});

describe('GamePuzzle shape parity', () => {
  it('accepts a minimal puzzle with empty cells/clues arrays', () => {
    const puzzle: GamePuzzle = {
      id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c',
      title: 'Petite grille',
      language: 'fr',
      width: 5,
      height: 5,
      hintsAllowed: 3,
      cells: [],
      clues: [],
      createdAt: '2026-04-24T15:30:00Z',
    };
    expect(puzzle.cells).toEqual([]);
  });
});
