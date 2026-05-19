import { act, fireEvent, render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type {
  GameEvent,
  PresenceUpdatedEvent,
  Unsubscribe,
} from '@/application/game';
import type { Player, Pseudonym, SessionId } from '@/domain/game';
import type { Cell, Puzzle } from '@/domain';
import { Grid } from '@/ui/components/grid';

// Multiplayer highlights — edge cases beyond `tests/presence-overlay.test.tsx`.
// Pins the precedence rules the redesign promises:
//   - Sage validation overrides every player treatment (no ring, no
//     word-tint, no badge on a validated cell).
//   - The local player paints with the SOLO classes (rose focusBg +
//     ring) so the cue stays consistent with single-player. Remote
//     peers keep their hash-derived hue (ADR-0018 §Presence).
//   - Most-recent-active wins on a shared word cell when the active
//     cells of two remote players don't collide.

const L = (row: number, col: number): Cell =>
  ({ kind: 'letter', position: { row, col }, entry: '' });

const TEST_PUZZLE: Puzzle = {
  id: 'test', title: 'test', language: 'fr', width: 5, height: 4, hintsAllowed: 3, hintsRemaining: 3,
  cells: [
    { kind: 'definition', position: { row: 0, col: 0 }, clues: [{ text: 'a', arrow: 'right' }] },
    L(0, 1),
    { kind: 'definition', position: { row: 0, col: 2 }, clues: [{ text: 'b', arrow: 'down' }] },
    L(0, 3), L(0, 4),
    { kind: 'definition', position: { row: 1, col: 0 }, clues: [{ text: 'c', arrow: 'right' }] },
    L(1, 1), L(1, 2), L(1, 3), L(1, 4),
    L(2, 0), L(2, 1), L(2, 2), L(2, 3), L(2, 4),
    L(3, 0),
    { kind: 'block', position: { row: 3, col: 1 } },
    L(3, 2), L(3, 3), L(3, 4),
  ],
};

const SESSION_ALICE = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c' as SessionId;
const SESSION_BOB = 'aaaa1111-7a2c-7c9e-8f1a-9b2d3e4f5a6c' as SessionId;
const SESSION_LOCAL = 'bbbb2222-7a2c-7c9e-8f1a-9b2d3e4f5a6c' as SessionId;

const players: Map<SessionId, Player> = new Map([
  [SESSION_LOCAL, { sessionId: SESSION_LOCAL, pseudonym: 'Me' as Pseudonym, joinedAt: '2026-05-02T15:30:00Z' }],
  [SESSION_ALICE, { sessionId: SESSION_ALICE, pseudonym: 'Alice' as Pseudonym, joinedAt: '2026-05-02T15:30:01Z' }],
  [SESSION_BOB, { sessionId: SESSION_BOB, pseudonym: 'Bob' as Pseudonym, joinedAt: '2026-05-02T15:30:02Z' }],
]);

interface FakeStream {
  readonly subscribe: (handler: (e: GameEvent) => void) => Unsubscribe;
  readonly dispatch: (e: PresenceUpdatedEvent) => void;
}
const makeFakeStream = (): FakeStream => {
  const subs = new Set<(e: GameEvent) => void>();
  return {
    subscribe: (handler) => { subs.add(handler); return () => { subs.delete(handler); }; },
    dispatch: (event) => { for (const s of [...subs]) s(event); },
  };
};

const presence = (
  sessionId: SessionId,
  row: number | null,
  column: number | null,
  direction: 'across' | 'down' | null = 'across',
): PresenceUpdatedEvent => ({
  type: 'presenceUpdated',
  sessionId,
  row,
  column,
  direction,
});

const cellAt = (root: HTMLElement, row: number, col: number) =>
  root.querySelector<HTMLElement>(
    `[role="gridcell"][data-row="${row}"][data-col="${col}"]`,
  );

const inputAt = (root: HTMLElement, row: number, col: number) =>
  root.querySelector<HTMLInputElement>(
    `[data-cell-kind="letter"][data-row="${row}"][data-col="${col}"]`,
  );

const click = (el: HTMLElement) => {
  el.focus();
  fireEvent.click(el);
};

describe('Multiplayer highlights — sage validation override', () => {
  it('subtracts validated cells from the presence map (no ring / no word-tint / no badge)', () => {
    const stream = makeFakeStream();
    // (1,2) is in Alice's across-2 word; mark it validated.
    const validated = new Set<string>(['1,1', '1,2', '1,3', '1,4']);
    const { container } = render(
      <Grid
        puzzle={TEST_PUZZLE}
        subscribeToRemotePresence={stream.subscribe}
        playersBySessionId={players}
        currentSessionId={SESSION_LOCAL}
        validatedPositions={validated}
      />,
    );
    act(() => stream.dispatch(presence(SESSION_ALICE, 1, 2, 'across')));
    // Alice's cursor is on a validated cell → no active class, no badge.
    expect(cellAt(container, 1, 2)?.getAttribute('data-player-active')).toBeNull();
    expect(container.querySelectorAll('[data-player-badge="true"]')).toHaveLength(0);
    // None of the row-1 letter cells should carry word-tint either.
    expect(container.querySelectorAll('[data-player-word="true"]')).toHaveLength(0);
  });
});

describe('Multiplayer highlights — local cursor', () => {
  it('paints data-player-word on the local player\'s trailing word cells', () => {
    const stream = makeFakeStream();
    const { container } = render(
      <Grid
        puzzle={TEST_PUZZLE}
        subscribeToRemotePresence={stream.subscribe}
        playersBySessionId={players}
        currentSessionId={SESSION_LOCAL}
      />,
    );
    // Click into (1,3): no clue starts at this cell, so the navigation
    // hook's "first click prefers a starting clue" rule has no candidate
    // and the initial 'across' direction is preserved. The across word
    // spans (1,1)..(1,4) → 3 word-tint cells with active at (1,3).
    act(() => click(inputAt(container, 1, 3)!));
    expect(cellAt(container, 1, 3)?.getAttribute('data-player-active')).toBe('true');
    const wordCells = container.querySelectorAll(
      '[role="gridcell"][data-player-word="true"]',
    );
    expect(wordCells).toHaveLength(3);
    // None of these word cells should carry a badge — local player gets
    // no badge per the spec.
    expect(container.querySelectorAll('[data-player-badge="true"]')).toHaveLength(0);
  });

  it('local active cell does NOT carry per-player CSS vars on its wrapper (paints with solo classes)', () => {
    const stream = makeFakeStream();
    const { container } = render(
      <Grid
        puzzle={TEST_PUZZLE}
        subscribeToRemotePresence={stream.subscribe}
        playersBySessionId={players}
        currentSessionId={SESSION_LOCAL}
      />,
    );
    act(() => click(inputAt(container, 1, 3)!));
    const active = cellAt(container, 1, 3)!;
    // Local-player cells never spread the hash-derived `--player-*`
    // vars on their wrapper — those are remote-only.
    expect(active.style.getPropertyValue('--player-color')).toBe('');
    expect(active.style.getPropertyValue('--player-active-bg')).toBe('');
    expect(active.style.getPropertyValue('--player-word-bg')).toBe('');
    const wordCell = cellAt(container, 1, 1)!;
    expect(wordCell.style.getPropertyValue('--player-word-bg')).toBe('');
  });

  it('remote active cell still carries per-player CSS vars on its wrapper', () => {
    const stream = makeFakeStream();
    const { container } = render(
      <Grid
        puzzle={TEST_PUZZLE}
        subscribeToRemotePresence={stream.subscribe}
        playersBySessionId={players}
        currentSessionId={SESSION_LOCAL}
      />,
    );
    act(() => stream.dispatch(presence(SESSION_ALICE, 1, 2, 'across')));
    const remoteActive = cellAt(container, 1, 2)!;
    // Remote peers keep their hash-derived hue (ADR-0018 §Presence).
    expect(remoteActive.style.getPropertyValue('--player-color')).not.toBe('');
    expect(remoteActive.style.getPropertyValue('--player-active-bg')).not.toBe('');
    // And the remote-active cell carries a badge.
    expect(container.querySelectorAll('[data-player-badge="true"]')).toHaveLength(1);
  });

  it('local active cell wins precedence over a remote whose word covers it', () => {
    const stream = makeFakeStream();
    const { container } = render(
      <Grid
        puzzle={TEST_PUZZLE}
        subscribeToRemotePresence={stream.subscribe}
        playersBySessionId={players}
        currentSessionId={SESSION_LOCAL}
      />,
    );
    // Bob solves down-2 starting at (1,2). His word covers (1,2)..(3,2).
    act(() => stream.dispatch(presence(SESSION_BOB, 2, 2, 'down')));
    // Local player puts their cursor on (1,2) — Bob's word includes
    // this cell, but the local active treatment wins on the local user's
    // own grid.
    act(() => click(inputAt(container, 1, 2)!));
    const shared = cellAt(container, 1, 2);
    expect(shared?.getAttribute('data-player-active')).toBe('true');
    // The local player gets no badge on their own active cell.
    const badges = container.querySelectorAll('[data-player-badge="true"]');
    // Bob's cursor is still on (2,2) and that gets a badge with "B".
    expect(badges).toHaveLength(1);
    expect(badges[0]?.textContent).toBe('B');
  });
});

describe('Multiplayer highlights — most-recent-wins on shared word cells', () => {
  it('a later remote\'s word claims an overlap cell from an earlier remote\'s word', () => {
    const stream = makeFakeStream();
    const { container } = render(
      <Grid
        puzzle={TEST_PUZZLE}
        subscribeToRemotePresence={stream.subscribe}
        playersBySessionId={players}
        currentSessionId={SESSION_LOCAL}
      />,
    );
    // Alice solves across at (1,1) → covers (1,1)..(1,4).
    // Bob arrives later, solving down at (0,2) → covers (0,2)..(2,2)
    // (definition cells stop the range at the top edge — wordRange
    // returns from the first letter cell). The shared cell is (1,2).
    act(() => {
      stream.dispatch(presence(SESSION_ALICE, 1, 1, 'across'));
      stream.dispatch(presence(SESSION_BOB, 1, 2, 'down'));
    });
    // (1,2) is Bob's active cell, so it carries data-player-active.
    expect(cellAt(container, 1, 2)?.getAttribute('data-player-active')).toBe('true');
    // (1,3) and (1,4) are Alice's word-only cells; they should still
    // carry data-player-word (Bob's word doesn't cover them).
    expect(cellAt(container, 1, 3)?.getAttribute('data-player-word')).toBe('true');
    expect(cellAt(container, 1, 4)?.getAttribute('data-player-word')).toBe('true');
  });
});
