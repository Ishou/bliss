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

// PresenceOverlay tests. The overlay is mounted as a sibling of the
// grid (`Grid` wraps it for us when `subscribeToRemotePresence` +
// `playersBySessionId` are present), so the simplest test surface is
// rendering Grid with those props and dispatching `presenceUpdated`
// events through the subscribe registrar.
//
// What we pin:
//   1. A presence frame mounts a ring + a chip + word-tint rects on
//      the focused cell's word range.
//   2. Multiple presences on the same cell stack as concentric rings
//      with distinct `data-stack-index`.
//   3. The chip carries the peer's pseudonym (looked up via the
//      `playersBySessionId` map prop).
//   4. Solo mode (no presence props) renders no overlay at all.

const L = (row: number, col: number): Cell =>
  ({ kind: 'letter', position: { row, col }, entry: '' });

const TEST_PUZZLE: Puzzle = {
  id: 'test', title: 'test', language: 'fr', width: 5, height: 4,
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
// Stand-in for the local player's session — the overlay filters out
// frames whose sessionId matches `currentSessionId` (own presence is
// already conveyed by `letterCellInWord` + the DOM caret), so tests use
// a third session id distinct from any peer being asserted.
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

describe('PresenceOverlay — single peer cursor', () => {
  it('renders one ring + one chip on the focused cell after a presenceUpdated dispatch', () => {
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
    const rings = container.querySelectorAll('[data-testid="presence-ring"]');
    expect(rings).toHaveLength(1);
    expect(rings[0]?.getAttribute('data-session-id')).toBe(SESSION_ALICE);
    const chips = container.querySelectorAll('[data-testid="presence-chip"]');
    expect(chips).toHaveLength(1);
    expect(chips[0]?.textContent).toContain('Alice');
  });

  it('renders one word-tint rect per cell in the focused word', () => {
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
    const tints = container.querySelectorAll(
      `[data-testid="presence-word-tint"][data-session-id="${SESSION_ALICE}"]`,
    );
    // across-2 spans (1,1)..(1,4) — 4 letter cells.
    expect(tints).toHaveLength(4);
  });

  it('drops the cursor when row/column arrive as null', () => {
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
    expect(container.querySelectorAll('[data-testid="presence-ring"]')).toHaveLength(1);
    act(() => stream.dispatch(presence(SESSION_ALICE, null, null, null)));
    expect(container.querySelectorAll('[data-testid="presence-ring"]')).toHaveLength(0);
    expect(container.querySelectorAll('[data-testid="presence-chip"]')).toHaveLength(0);
  });

  it('drops a presence frame whose sessionId matches currentSessionId (own cursor never overlaid)', () => {
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
    // Server echoes the local player's own cellFocus back via presenceUpdated;
    // the overlay must NOT paint a ring for the local player on top of the
    // existing letterCellInWord highlight.
    act(() => stream.dispatch(presence(SESSION_LOCAL, 1, 2, 'across')));
    expect(container.querySelectorAll('[data-testid="presence-ring"]')).toHaveLength(0);
    // A peer presence on the same dispatch round still renders.
    act(() => stream.dispatch(presence(SESSION_ALICE, 2, 2, 'down')));
    expect(container.querySelectorAll('[data-testid="presence-ring"]')).toHaveLength(1);
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
    expect(container.querySelectorAll('[data-testid="presence-ring"]')).toHaveLength(0);
  });
});

describe('PresenceOverlay — overlapping presences', () => {
  it('stacks concentric rings on the same cell with distinct stack indexes', () => {
    const stream = makeFakeStream();
    const { container } = render(
      <Grid
        puzzle={TEST_PUZZLE}
        subscribeToRemotePresence={stream.subscribe}
        playersBySessionId={players}
        currentSessionId={SESSION_LOCAL}
      />,
    );
    act(() => {
      stream.dispatch(presence(SESSION_ALICE, 2, 2, 'across'));
      stream.dispatch(presence(SESSION_BOB, 2, 2, 'down'));
    });
    const rings = container.querySelectorAll('[data-testid="presence-ring"]');
    expect(rings).toHaveLength(2);
    const indexes = Array.from(rings).map((r) => r.getAttribute('data-stack-index')).sort();
    expect(indexes).toEqual(['0', '1']);
    // Both pseudonym chips render at the same anchor cell.
    const chips = container.querySelectorAll('[data-testid="presence-chip"]');
    expect(chips).toHaveLength(2);
    const chipText = Array.from(chips).map((c) => c.textContent ?? '').join(' ');
    expect(chipText).toContain('Alice');
    expect(chipText).toContain('Bob');
  });
});

describe('PresenceOverlay — solo mode', () => {
  it('does not mount the overlay when neither presence prop is provided', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    expect(container.querySelector('[data-testid="presence-overlay"]')).toBeNull();
  });

  it('does not mount the overlay when only one of the two presence props is provided', () => {
    const stream = makeFakeStream();
    const { container } = render(
      <Grid puzzle={TEST_PUZZLE} subscribeToRemotePresence={stream.subscribe} />,
    );
    expect(container.querySelector('[data-testid="presence-overlay"]')).toBeNull();
  });
});
