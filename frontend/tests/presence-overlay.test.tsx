import { act, render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type {
  GameEvent,
  PresenceUpdatedEvent,
  Unsubscribe,
} from '@/application/game';
import type { Player, Pseudonym, SessionId } from '@/domain/game';
import type { Cell, Puzzle } from '@/domain';
import { Grid } from '@/ui/components/grid';

// Multiplayer presence visuals — post-redesign: the per-cell active ring,
// word-tint, and badge live INSIDE each `LetterCellView` (no overlay
// sibling). These tests assert on the cell-level data attributes the
// builder wires:
//   - `data-player-active="true"` on the cell whose cursor a peer occupies
//   - `data-player-word="true"`   on cells inside the peer's word range
//   - `data-player-badge="true"`  on the badge span (only on remote-active cells)

const L = (row: number, col: number): Cell =>
  ({ kind: 'letter', position: { row, col }, entry: '' });

const TEST_PUZZLE: Puzzle = {
  id: 'test', title: 'test', language: 'fr', width: 5, height: 4, hintsAllowed: 3,
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
  [SESSION_ALICE, { sessionId: SESSION_ALICE, pseudonym: 'Alice' as Pseudonym, joinedAt: '2026-05-02T15:30:00Z' }],
  [SESSION_BOB, { sessionId: SESSION_BOB, pseudonym: 'Bob' as Pseudonym, joinedAt: '2026-05-02T15:30:01Z' }],
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

describe('Presence visuals — single peer cursor', () => {
  it('marks the focused cell with data-player-active and renders a badge', () => {
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
    const active = cellAt(container, 1, 2);
    expect(active?.getAttribute('data-player-active')).toBe('true');
    const badges = container.querySelectorAll('[data-player-badge="true"]');
    expect(badges).toHaveLength(1);
    expect(badges[0]?.textContent).toBe('A');
  });

  it('marks every other cell in the peer\'s word range with data-player-word', () => {
    const stream = makeFakeStream();
    const { container } = render(
      <Grid
        puzzle={TEST_PUZZLE}
        subscribeToRemotePresence={stream.subscribe}
        playersBySessionId={players}
        currentSessionId={SESSION_LOCAL}
      />,
    );
    act(() => stream.dispatch(presence(SESSION_ALICE, 1, 3, 'across')));
    const wordCells = container.querySelectorAll(
      '[role="gridcell"][data-player-word="true"]',
    );
    // across-2 spans (1,1)..(1,4) — 4 letter cells; 1 of them is the
    // active cell so 3 word-tinted cells remain.
    expect(wordCells).toHaveLength(3);
    expect(cellAt(container, 1, 3)?.getAttribute('data-player-active')).toBe('true');
  });

  it('clears the cell highlight when row/column arrive as null', () => {
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
    expect(cellAt(container, 1, 2)?.getAttribute('data-player-active')).toBe('true');
    act(() => stream.dispatch(presence(SESSION_ALICE, null, null, null)));
    expect(cellAt(container, 1, 2)?.getAttribute('data-player-active')).toBeNull();
    expect(container.querySelectorAll('[data-player-badge="true"]')).toHaveLength(0);
  });

  it('drops a presence frame whose sessionId matches currentSessionId (own cursor not painted as remote)', () => {
    const stream = makeFakeStream();
    const localPlayers: Map<SessionId, Player> = new Map([
      [SESSION_LOCAL, { sessionId: SESSION_LOCAL, pseudonym: 'Me' as Pseudonym, joinedAt: '2026-05-02T15:30:00Z' }],
      [SESSION_ALICE, { sessionId: SESSION_ALICE, pseudonym: 'Alice' as Pseudonym, joinedAt: '2026-05-02T15:30:01Z' }],
    ]);
    const { container } = render(
      <Grid
        puzzle={TEST_PUZZLE}
        subscribeToRemotePresence={stream.subscribe}
        playersBySessionId={localPlayers}
        currentSessionId={SESSION_LOCAL}
      />,
    );
    act(() => stream.dispatch(presence(SESSION_LOCAL, 1, 2, 'across')));
    // No badge should render for the local session even when the server
    // echoes the local user's own cellFocus back.
    expect(container.querySelectorAll('[data-player-badge="true"]')).toHaveLength(0);
    act(() => stream.dispatch(presence(SESSION_ALICE, 2, 2, 'down')));
    const aliceBadges = container.querySelectorAll('[data-player-badge="true"]');
    expect(aliceBadges).toHaveLength(1);
    expect(aliceBadges[0]?.textContent).toBe('A');
  });

  it('drops a presence frame for a session not in playersBySessionId', () => {
    const stream = makeFakeStream();
    const ghost = 'ffff9999-7a2c-7c9e-8f1a-9b2d3e4f5a6c' as SessionId;
    const { container } = render(
      <Grid
        puzzle={TEST_PUZZLE}
        subscribeToRemotePresence={stream.subscribe}
        playersBySessionId={players}
        currentSessionId={SESSION_LOCAL}
      />,
    );
    act(() => stream.dispatch(presence(ghost, 1, 2, 'across')));
    expect(container.querySelectorAll('[data-player-active="true"]')).toHaveLength(0);
    expect(container.querySelectorAll('[data-player-badge="true"]')).toHaveLength(0);
  });
});

describe('Presence visuals — overlapping presences', () => {
  it('most-recently-active wins on a shared word cell; only one cell carries data-player-word', () => {
    const stream = makeFakeStream();
    const { container } = render(
      <Grid
        puzzle={TEST_PUZZLE}
        subscribeToRemotePresence={stream.subscribe}
        playersBySessionId={players}
        currentSessionId={SESSION_LOCAL}
      />,
    );
    // Alice solving across-2 (row 1) covers (1,1)..(1,4). Bob solving
    // down-2 (col 2) covers (1,2)..(3,2). Cell (1,2) is in both ranges.
    // Bob arrives last, so (1,2) should belong to Bob's word — but
    // Alice's cursor IS on (1,2), making (1,2) Alice's ACTIVE cell.
    // Active wins over word, so the cell carries data-player-active.
    act(() => {
      stream.dispatch(presence(SESSION_ALICE, 1, 2, 'across'));
      stream.dispatch(presence(SESSION_BOB, 2, 2, 'down'));
    });
    const shared = cellAt(container, 1, 2);
    expect(shared?.getAttribute('data-player-active')).toBe('true');
    // Two distinct active cells (Alice on (1,2), Bob on (2,2)).
    const actives = container.querySelectorAll('[data-player-active="true"]');
    expect(actives).toHaveLength(2);
  });
});

describe('Presence visuals — solo mode', () => {
  it('does not paint any presence attributes when no presence props are supplied', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    expect(container.querySelectorAll('[data-player-active="true"]')).toHaveLength(0);
    expect(container.querySelectorAll('[data-player-word="true"]')).toHaveLength(0);
    expect(container.querySelectorAll('[data-player-badge="true"]')).toHaveLength(0);
  });

  it('does not enable presence when only one of the two presence props is provided', () => {
    const stream = makeFakeStream();
    const { container } = render(
      <Grid puzzle={TEST_PUZZLE} subscribeToRemotePresence={stream.subscribe} />,
    );
    act(() => stream.dispatch(presence(SESSION_ALICE, 1, 2, 'across')));
    expect(container.querySelectorAll('[data-player-active="true"]')).toHaveLength(0);
  });
});
