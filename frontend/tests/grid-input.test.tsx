import { render, fireEvent, act } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import type { Cell, Puzzle } from '@/domain';
import { Grid } from '@/ui/components/grid';

// Inline test fixture (per workstream guidance, do not import the
// shared SAMPLE_PUZZLE — the parallel sample-coverage workstream is
// rewriting it). 5×4 grid laid out so cell (1,2) is the intersection
// of an across clue (def at (1,0)) and a down clue (def at (0,2)).
//
// Layout — D = definition, B = block, X = letter:
//   D→  X   D↓  X   X     across-1: (0,1)
//                          down-1:   (1,2),(2,2),(3,2)
//   D→  X   X   X   X     across-2: (1,1)..(1,4)
//   X   X   X   X   X
//   X   B   X   X   X
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
const defAt = (root: HTMLElement, row: number, col: number) =>
  root.querySelector<HTMLElement>(`[data-cell-kind="definition"][data-row="${row}"][data-col="${col}"]`);

// We avoid userEvent because @testing-library/user-event is not in the
// dep set (no-new-deps constraint from the workstream brief). The
// orchestration intercepts every key in onKeyDown, so fireEvent on the
// synthetic events React listens to is sufficient. `click` mirrors what
// a real browser does on tap: focus moves to the input *first* (the
// browser's click default action), then the click event fires. jsdom
// doesn't auto-focus on `fireEvent.click`, so the helper does it
// explicitly. In production we removed the explicit `focusCell` call
// from the click handler so the soft keyboard isn't suppressed on
// Android / iOS — the browser's native focus-on-click handles it
// instead, and `handleFocus` reads the resulting focus event.
const click = (el: HTMLElement) => { el.focus(); fireEvent.click(el); };
const typeChar = (el: HTMLInputElement, ch: string) => fireEvent.keyDown(el, { key: ch });

describe('Grid keyboard interactions', () => {
  it('clicking a letter cell focuses it and highlights the current word', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const target = inputAt(container, 1, 1)!;
    click(target);
    expect(document.activeElement).toBe(target);
    // Focused cell is excluded from currentWord (its `_focus` style is
    // already the strongest visual).
    expect(wrapAt(container, 1, 1)?.dataset.inWord).toBe('false');
    expect(wrapAt(container, 1, 2)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('true');
    expect(defAt(container, 1, 0)?.dataset.currentClue).toBe('true');
  });

  it('typing a letter writes it and advances along the current direction', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const start = inputAt(container, 1, 1)!;
    click(start);
    typeChar(start, 'l');
    expect(start.value).toBe('L');
    expect(document.activeElement).toBe(inputAt(container, 1, 2));
  });

  it('typing past the end of the current word does not advance or crash', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const last = inputAt(container, 1, 4)!;
    click(last);
    typeChar(last, 'z');
    expect(last.value).toBe('Z');
    expect(document.activeElement).toBe(last);
  });

  it('Backspace on a filled cell clears it without moving focus', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const target = inputAt(container, 1, 2)!;
    click(target);
    typeChar(target, 'x');
    click(target); // typeChar advanced focus; come back.
    expect(target.value).toBe('X');
    fireEvent.keyDown(target, { key: 'Backspace' });
    expect(target.value).toBe('');
    expect(document.activeElement).toBe(target);
  });

  it('Backspace on an empty cell moves focus backward and clears that cell', () => {
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

  it('arrow keys move focus and switch direction when perpendicular', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const start = inputAt(container, 1, 2)!;
    click(start);
    // Direction starts 'across'; first ArrowDown only flips direction.
    fireEvent.keyDown(start, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(start);
    fireEvent.keyDown(start, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(inputAt(container, 2, 2));
    const at22 = inputAt(container, 2, 2)!;
    fireEvent.keyDown(at22, { key: 'ArrowRight' });
    expect(document.activeElement).toBe(at22);
    fireEvent.keyDown(at22, { key: 'ArrowRight' });
    expect(document.activeElement).toBe(inputAt(container, 2, 3));
  });

  it('clicking the focused cell toggles direction (NYT-style)', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const target = inputAt(container, 1, 2)!;
    click(target);
    expect(wrapAt(container, 1, 3)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 2, 2)?.dataset.inWord).toBe('false');
    act(() => click(target));
    expect(wrapAt(container, 1, 3)?.dataset.inWord).toBe('false');
    expect(wrapAt(container, 2, 2)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 3, 2)?.dataset.inWord).toBe('true');
  });

  // Android Gboard / Samsung keyboards fire `keydown` with
  // `key === "Unidentified"` for printable characters, so the desktop
  // keydown path doesn't help. The real letter only arrives on the
  // `input` event via `InputEvent.data`. This test simulates that
  // soft-keyboard shape by setting the input value and dispatching a
  // native InputEvent (fireEvent.input doesn't expose `inputType` or
  // `data` on the synthetic event).
  it('Android soft-keyboard input writes uppercase and advances focus', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const start = inputAt(container, 1, 1)!;
    click(start);
    start.value = 'l';
    start.dispatchEvent(new InputEvent('input', { inputType: 'insertText', data: 'l', bubbles: true }));
    expect(start.value).toBe('L');
    expect(document.activeElement).toBe(inputAt(container, 1, 2));
  });

  it('handleInput blanks the cell when a paste produces multi-char content', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const cell = inputAt(container, 1, 1)!;
    click(cell);
    cell.value = 'bonjour';
    cell.dispatchEvent(new InputEvent('input', { inputType: 'insertFromPaste', data: null, bubbles: true }));
    expect(cell.value).toBe('');
  });

  it('handleInput blanks the cell when insertText data is not a single letter', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const cell = inputAt(container, 1, 1)!;
    click(cell);
    cell.value = '12';
    cell.dispatchEvent(new InputEvent('input', { inputType: 'insertText', data: '12', bubbles: true }));
    expect(cell.value).toBe('');
  });
});
