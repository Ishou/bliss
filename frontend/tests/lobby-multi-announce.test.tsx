import { describe, expect, it } from 'vitest';
import { multiAnnouncementFor, type MultiAnnounceContext } from '@/ui/routes/lobby.$lobbyId.lazy';
import type { GameEvent } from '@/application/game';

const baseCtx: MultiAnnounceContext = {
  localSessionId: 'local-session',
  pseudonymBySessionId: new Map([
    ['session-alice', 'Alice'],
    ['session-bob', 'Bob'],
  ]),
  readLetterAt: () => '',
};

describe('multiAnnouncementFor', () => {
  it('announces "<name> a rejoint la partie" for a remote playerJoined', () => {
    const event = {
      type: 'playerJoined',
      sessionId: 'session-alice',
      pseudonym: 'Alice',
      joinedAt: '2026-05-10T00:00:00Z',
    } as unknown as GameEvent;
    expect(multiAnnouncementFor(event, baseCtx)).toBe('Alice a rejoint la partie');
  });

  it("returns null for the local user's own playerJoined", () => {
    const event = {
      type: 'playerJoined',
      sessionId: 'local-session',
      pseudonym: 'Self',
      joinedAt: '2026-05-10T00:00:00Z',
    } as unknown as GameEvent;
    expect(multiAnnouncementFor(event, baseCtx)).toBeNull();
  });

  it('announces "<name> a quitté la partie" for a remote playerLeft', () => {
    const event = { type: 'playerLeft', sessionId: 'session-bob' } as unknown as GameEvent;
    expect(multiAnnouncementFor(event, baseCtx)).toBe('Bob a quitté la partie');
  });

  it('falls back to "Un joueur" when pseudonym is unknown', () => {
    const event = { type: 'playerLeft', sessionId: 'session-unknown' } as unknown as GameEvent;
    expect(multiAnnouncementFor(event, baseCtx)).toBe('Un joueur a quitté la partie');
  });

  it("returns null for the local user's own playerLeft", () => {
    const event = { type: 'playerLeft', sessionId: 'local-session' } as unknown as GameEvent;
    expect(multiAnnouncementFor(event, baseCtx)).toBeNull();
  });

  it('announces "La partie commence" on gameStarted', () => {
    const event = {
      type: 'gameStarted',
      puzzle: {} as unknown,
      startedAt: '2026-05-10T00:00:00Z',
    } as unknown as GameEvent;
    expect(multiAnnouncementFor(event, baseCtx)).toBe('La partie commence');
  });

  it('reads letters via readLetterAt and announces "mot validé : WORD"', () => {
    const letters = new Map([
      ['0,1', 'C'],
      ['0,2', 'H'],
      ['0,3', 'A'],
      ['0,4', 'T'],
    ]);
    const ctx: MultiAnnounceContext = {
      ...baseCtx,
      readLetterAt: (row, col) => letters.get(`${row},${col}`) ?? '',
    };
    const event = {
      type: 'wordLocked',
      positions: [
        { row: 0, column: 1 }, { row: 0, column: 2 },
        { row: 0, column: 3 }, { row: 0, column: 4 },
      ],
      lockedAt: '2026-05-10T00:00:00Z',
    } as unknown as GameEvent;
    expect(multiAnnouncementFor(event, ctx)).toBe('mot validé : CHAT');
  });

  it('returns null for wordLocked when all positions are empty', () => {
    const event = {
      type: 'wordLocked',
      positions: [{ row: 0, column: 1 }],
      lockedAt: '2026-05-10T00:00:00Z',
    } as unknown as GameEvent;
    expect(multiAnnouncementFor(event, { ...baseCtx, readLetterAt: () => '' })).toBeNull();
  });

  it('returns null for unrelated events (cellUpdated, presenceUpdated)', () => {
    const cell = {
      type: 'cellUpdated',
      sessionId: 'session-alice',
      row: 0, column: 1, letter: 'X',
      writtenAt: '2026-05-10T00:00:00Z',
    } as unknown as GameEvent;
    expect(multiAnnouncementFor(cell, baseCtx)).toBeNull();

    const presence = {
      type: 'presenceUpdated',
      sessionId: 'session-alice',
      row: 0, column: 1, direction: 'across',
    } as unknown as GameEvent;
    expect(multiAnnouncementFor(presence, baseCtx)).toBeNull();
  });
});
