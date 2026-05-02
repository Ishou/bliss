import { render, fireEvent, act } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import type { CellUpdatedEvent, GameEvent, Unsubscribe } from '@/application/game';
import type { Instant, Letter, SessionId } from '@/domain/game';
import type { Cell, Puzzle } from '@/domain';
import { Grid } from '@/ui/components/grid';

// Wave H · PR #19. Inbound path: a `cellUpdated` frame from the
// WebSocket lands in the uncontrolled <input> at (row, column) without
// going through the local typing pipeline. Pins three contracts:
//   1. The remote write reaches `el.value` of the right cell (and
//      `letter: null` clears it).
//   2. The remote write does NOT re-fire `onCellChange` (otherwise the
//      multiplayer client would echo every server broadcast back to the
//      server and create an infinite loop).
//   3. Local typing AND local Backspace AFTER a remote write still
//      detect "same letter" / "already empty" no-op state correctly —
//      i.e. the inbound path keeps the per-cell mirror that
//      handleInput/handleKeyDown rely on consistent.
//   4. Without the prop, Grid's behaviour is identical to before
//      (regression net for solo mode at `/`).

// Same fixture as grid-input.test.tsx — kept inline so the tests stay
// independent. 5×4 grid; (1,1)..(1,4) is across-2; (1,2),(2,2),(3,2) is
// down-1 starting at definition (0,2).
const L = (row: number, col: number, answer: string): Cell =>
  ({ kind: 'letter', position: { row, col }, answer, entry: '' });

const TEST_PUZZLE: Puzzle = {
  id: 'test', title: 'test', language: 'fr', width: 5, height: 4,
  cells: [
    { kind: 'definition', position: { row: 0, col: 0 }, clues: [{ text: 'across-1', arrow: 'right' }] },
    L(0, 1, 'A'),
    { kind: 'definition', position: { row: 0, col: 2 }, clues: [{ text: 'down-1', arrow: 'down' }] },
    L(0, 3, 'B'), L(0, 4, 'C'),
    { kind: 'definition', position: { row: 1, col: 0 }, clues: [{ text: 'across-2', arrow: 'right' }] },
    L(1, 1, 'D'), L(1, 2, 'E'), L(1, 3, 'F'), L(1, 4, 'G'),
    L(2, 0, 'H'), L(2, 1, 'I'), L(2, 2, 'J'), L(2, 3, 'K'), L(2, 4, 'L'),
    L(3, 0, 'M'),
    { kind: 'block', position: { row: 3, col: 1 } },
    L(3, 2, 'N'), L(3, 3, 'O'), L(3, 4, 'P'),
  ],
};

const inputAt = (root: HTMLElement, row: number, col: number) =>
  root.querySelector<HTMLInputElement>(`[data-cell-kind="letter"][data-row="${row}"][data-col="${col}"]`);
const wrapAt = (root: HTMLElement, row: number, col: number) =>
  inputAt(root, row, col)?.parentElement ?? null;

const click = (el: HTMLElement) => { el.focus(); fireEvent.click(el); };
const typeChar = (el: HTMLInputElement, ch: string) => fireEvent.keyDown(el, { key: ch });

const REMOTE_SESSION = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c' as SessionId;
const remoteUpdate = (row: number, column: number, letter: string | null): CellUpdatedEvent => ({
  type: 'cellUpdated',
  sessionId: REMOTE_SESSION,
  row,
  column,
  letter: (letter as Letter | null),
  writtenAt: '2026-05-02T15:30:00Z' as Instant,
});

// Test harness mirroring the `subscribe`-style registrar shape that
// `GameClient.subscribe` exposes. The Grid component attaches one
// handler on mount and detaches it on unmount — we expose `dispatch` so
// each test can drive frames into the grid and assert outcomes.
interface FakeRemoteStream {
  readonly subscribe: (handler: (e: GameEvent) => void) => Unsubscribe;
  readonly dispatch: (e: CellUpdatedEvent) => void;
  readonly subscriberCount: () => number;
}
const makeFakeRemoteStream = (): FakeRemoteStream => {
  const subscribers = new Set<(e: GameEvent) => void>();
  return {
    subscribe: (handler) => {
      subscribers.add(handler);
      return () => { subscribers.delete(handler); };
    },
    dispatch: (event) => { for (const s of subscribers) s(event); },
    subscriberCount: () => subscribers.size,
  };
};

describe('Grid remote cell update — inbound multiplayer path', () => {
  it('writes the letter into the matching uncontrolled <input>', () => {
    const stream = makeFakeRemoteStream();
    const { container } = render(
      <Grid puzzle={TEST_PUZZLE} subscribeToRemoteCellUpdates={stream.subscribe} />,
    );
    act(() => stream.dispatch(remoteUpdate(1, 2, 'X')));
    expect(inputAt(container, 1, 2)?.value).toBe('X');
  });

  it('clears the cell when the remote update carries letter: null', () => {
    const stream = makeFakeRemoteStream();
    const { container } = render(
      <Grid puzzle={TEST_PUZZLE} subscribeToRemoteCellUpdates={stream.subscribe} />,
    );
    // Pre-fill with a remote write so we can verify the clear actually
    // changes the DOM value (rather than asserting a never-set field).
    act(() => stream.dispatch(remoteUpdate(2, 3, 'Z')));
    expect(inputAt(container, 2, 3)?.value).toBe('Z');
    act(() => stream.dispatch(remoteUpdate(2, 3, null)));
    expect(inputAt(container, 2, 3)?.value).toBe('');
  });

  it('does not re-fire onCellChange — the inbound path must not echo back to the server', () => {
    const onCellChange = vi.fn();
    const stream = makeFakeRemoteStream();
    render(
      <Grid
        puzzle={TEST_PUZZLE}
        onCellChange={onCellChange}
        subscribeToRemoteCellUpdates={stream.subscribe}
      />,
    );
    act(() => stream.dispatch(remoteUpdate(1, 1, 'R')));
    act(() => stream.dispatch(remoteUpdate(1, 1, null)));
    expect(onCellChange).not.toHaveBeenCalled();
  });

  it('does not steal focus from the local user when a remote update arrives', () => {
    const stream = makeFakeRemoteStream();
    const { container } = render(
      <Grid puzzle={TEST_PUZZLE} subscribeToRemoteCellUpdates={stream.subscribe} />,
    );
    const focused = inputAt(container, 1, 1)!;
    click(focused);
    // A remote write into a different cell must not yank the caret.
    act(() => stream.dispatch(remoteUpdate(2, 4, 'Q')));
    expect(document.activeElement).toBe(focused);
    expect(inputAt(container, 2, 4)?.value).toBe('Q');
  });

  it('keeps the per-cell value mirror in sync so a same-letter local re-type is a no-op', () => {
    // After a remote write places "L" at (1,1), the local user typing
    // "L" at the same cell must NOT fire onCellChange (no value change).
    // This pins the contract that applyRemoteCellUpdate updates the
    // mirror cellValuesRef, not just the DOM.
    const onCellChange = vi.fn();
    const stream = makeFakeRemoteStream();
    const { container } = render(
      <Grid
        puzzle={TEST_PUZZLE}
        onCellChange={onCellChange}
        subscribeToRemoteCellUpdates={stream.subscribe}
      />,
    );
    act(() => stream.dispatch(remoteUpdate(1, 1, 'L')));
    const start = inputAt(container, 1, 1)!;
    click(start);
    onCellChange.mockClear();
    // Android-style InputEvent path: same letter, value already 'L'.
    start.value = 'l';
    start.dispatchEvent(new InputEvent('input', { inputType: 'insertText', data: 'l', bubbles: true }));
    expect(onCellChange).not.toHaveBeenCalled();
  });

  it('detaches the subscriber on unmount', () => {
    const stream = makeFakeRemoteStream();
    const { unmount } = render(
      <Grid puzzle={TEST_PUZZLE} subscribeToRemoteCellUpdates={stream.subscribe} />,
    );
    expect(stream.subscriberCount()).toBe(1);
    unmount();
    expect(stream.subscriberCount()).toBe(0);
  });

  it('ignores remote writes for positions that are not registered letter cells', () => {
    // (3,1) is a block cell in the fixture; (10,10) is out of bounds.
    // Either case should be a no-op, not a crash.
    const stream = makeFakeRemoteStream();
    expect(() => {
      render(<Grid puzzle={TEST_PUZZLE} subscribeToRemoteCellUpdates={stream.subscribe} />);
      act(() => stream.dispatch(remoteUpdate(3, 1, 'X')));
      act(() => stream.dispatch(remoteUpdate(10, 10, 'Y')));
    }).not.toThrow();
  });
});

describe('Grid solo mode regression — no subscribeToRemoteCellUpdates prop', () => {
  // The most important guarantee of this PR: passing nothing keeps
  // ADR-0002 §4 behaviour exactly as before — uncontrolled inputs,
  // local-only writes, no subscription effect mounted. This block
  // duplicates a couple of grid-input.test.tsx assertions on purpose
  // so a future refactor that accidentally couples the inbound effect
  // to the local path fails here too.

  it('local typing still writes and advances along the current direction', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const start = inputAt(container, 1, 1)!;
    click(start);
    typeChar(start, 'l');
    expect(start.value).toBe('L');
    expect(document.activeElement).toBe(inputAt(container, 1, 2));
  });

  it('clicking still highlights the current word', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    click(inputAt(container, 1, 1)!);
    expect(wrapAt(container, 1, 2)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('true');
  });

  it('Backspace on an empty cell still walks back and clears the previous cell', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const first = inputAt(container, 1, 1)!;
    click(first);
    typeChar(first, 'a');
    const second = inputAt(container, 1, 2)!;
    expect(document.activeElement).toBe(second);
    fireEvent.keyDown(second, { key: 'Backspace' });
    expect(document.activeElement).toBe(first);
    expect(first.value).toBe('');
  });
});
