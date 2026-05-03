import { fireEvent, render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Cell, Puzzle } from '@/domain';
import { Grid } from '@/ui/components/grid';

// Outbound presence path: clicking a letter cell or auto-advancing
// after a keystroke fires `onLocalFocusChange(position, direction)`
// per the hook's focus / direction transitions. The 200 ms debounce
// lives in `WebSocketGameClient` (`tests/websocket-game-client.test.ts`),
// so this file pins only the hook's surface contract — every transition
// invokes the callback.

const L = (row: number, col: number): Cell =>
  ({ kind: 'letter', position: { row, col }, entry: '' });

const TEST_PUZZLE: Puzzle = {
  id: 'test', title: 'test', language: 'fr', width: 5, height: 4,
  cells: [
    { kind: 'definition', position: { row: 0, col: 0 }, clues: [{ text: 'a', arrow: 'right' }] },
    L(0, 1), L(0, 2), L(0, 3), L(0, 4),
    { kind: 'definition', position: { row: 1, col: 0 }, clues: [{ text: 'c', arrow: 'right' }] },
    L(1, 1), L(1, 2), L(1, 3), L(1, 4),
  ],
};

const inputAt = (root: HTMLElement, row: number, col: number) =>
  root.querySelector<HTMLInputElement>(`[data-cell-kind="letter"][data-row="${row}"][data-col="${col}"]`);
const click = (el: HTMLElement) => { el.focus(); fireEvent.click(el); };

describe('Grid — onLocalFocusChange', () => {
  it('fires after the user clicks a letter cell with the new (position, direction)', () => {
    const onLocalFocusChange = vi.fn();
    const { container } = render(
      <Grid puzzle={TEST_PUZZLE} onLocalFocusChange={onLocalFocusChange} />,
    );
    onLocalFocusChange.mockClear(); // initial mount fires (null, null)
    click(inputAt(container, 1, 2)!);
    // Last call should reflect the focused cell with `across` direction.
    const calls = onLocalFocusChange.mock.calls;
    const last = calls[calls.length - 1];
    expect(last[0]).toEqual({ row: 1, col: 2 });
    expect(last[1]).toBe('across');
  });

  it('fires again when typing auto-advances along the word', () => {
    const onLocalFocusChange = vi.fn();
    const { container } = render(
      <Grid puzzle={TEST_PUZZLE} onLocalFocusChange={onLocalFocusChange} />,
    );
    const start = inputAt(container, 1, 1)!;
    click(start);
    onLocalFocusChange.mockClear();
    fireEvent.keyDown(start, { key: 'a' });
    // The keystroke advanced focus to (1,2) — at least one new
    // (position, direction) tuple should have been emitted with col 2.
    const positions = onLocalFocusChange.mock.calls.map((c) => c[0]);
    expect(positions.some((p) => p && p.row === 1 && p.col === 2)).toBe(true);
  });

  it('emits (null, null) when focus leaves the grid', () => {
    const onLocalFocusChange = vi.fn();
    const { container } = render(
      <Grid puzzle={TEST_PUZZLE} onLocalFocusChange={onLocalFocusChange} />,
    );
    const cell = inputAt(container, 1, 1)!;
    click(cell);
    onLocalFocusChange.mockClear();
    fireEvent.blur(cell);
    expect(onLocalFocusChange).toHaveBeenCalledWith(null, null);
  });
});

describe('Grid — solo mode regression for the new presence props', () => {
  it('still renders without the new presence callbacks (solo mode)', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    expect(container.querySelector('[role="grid"]')).not.toBeNull();
  });
});
