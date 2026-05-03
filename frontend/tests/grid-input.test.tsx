import { render, fireEvent, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
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
  it('clicking the wrapper div focuses the inner input', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const wrapper = wrapAt(container, 1, 1)!;
    fireEvent.click(wrapper);
    expect(document.activeElement).toBe(inputAt(container, 1, 1));
  });

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

  it('clears the word highlight when focus leaves the grid', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const target = inputAt(container, 1, 1)!;
    click(target);
    // Pre-blur sanity: the cells along across-2 are highlighted as in-word.
    expect(wrapAt(container, 1, 2)?.dataset.inWord).toBe('true');
    expect(defAt(container, 1, 0)?.dataset.currentClue).toBe('true');
    // Blur the input — simulates the user tapping outside the grid (the
    // page chrome, the URL bar, the keyboard's "done" button, etc.).
    // act() flushes the React state update synchronously so the DOM
    // assertions below see the post-blur render.
    act(() => target.blur());
    // Word highlight + current-clue stripe must clear; otherwise the
    // grid keeps a stale "this row is selected" appearance after the
    // user has visibly de-selected it.
    expect(wrapAt(container, 1, 2)?.dataset.inWord).toBe('false');
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('false');
    expect(defAt(container, 1, 0)?.dataset.currentClue).toBe('false');
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
    // Click (1,1) — first of across-2, sets direction = across. Move via
    // ArrowRight to (1,2) so we land there with direction still 'across'
    // (clicking (1,2) directly would now set direction = 'down' because
    // (1,2) is the first cell of down-1; navigation doesn't apply that
    // starting-clue rule).
    click(inputAt(container, 1, 1)!);
    fireEvent.keyDown(inputAt(container, 1, 1)!, { key: 'ArrowRight' });
    const start = inputAt(container, 1, 2)!;
    expect(document.activeElement).toBe(start);
    // First ArrowDown flips direction; second moves down.
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

  // Two related behaviors at multi-clue cells:
  //   1. First click at the FIRST CELL of a word focuses that word,
  //      regardless of the user's previous direction.
  //   2. Repeat-click on the same cell still toggles direction across
  //      ALL clues that pass through it (NYT-style).
  // Cell (1,2) is first of down-1 AND middle of across-2 — perfect for
  // pinning both behaviors.
  it('clicking the first cell of a word focuses that word, then toggles on repeat', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    // Pre-condition: previous selection on across (across-2 starts at (1,1)).
    click(inputAt(container, 1, 1)!);
    // Now click (1,2) — first letter of down-1. Direction must SWITCH to
    // 'down' even though across-2 (the previously active clue) also
    // passes through (1,2).
    const target = inputAt(container, 1, 2)!;
    click(target);
    // down-1 highlighted: cells (2,2) and (3,2) are in-word. across-2's
    // (1,3)/(1,4) are not.
    expect(wrapAt(container, 2, 2)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 3, 2)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 1, 3)?.dataset.inWord).toBe('false');
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('false');
    expect(defAt(container, 0, 2)?.dataset.currentClue).toBe('true'); // down-1 def
    expect(defAt(container, 1, 0)?.dataset.currentClue).toBe('false'); // across-2 def
    // Repeat-click toggles to the other clue passing through (1,2) — across-2.
    act(() => click(target));
    expect(wrapAt(container, 1, 3)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 2, 2)?.dataset.inWord).toBe('false');
    expect(wrapAt(container, 3, 2)?.dataset.inWord).toBe('false');
    expect(defAt(container, 1, 0)?.dataset.currentClue).toBe('true');
    expect(defAt(container, 0, 2)?.dataset.currentClue).toBe('false');
  });

  // Pins the regression that prompted the lastClickedRef refactor: even if
  // the input blurs between two same-cell clicks (iOS soft-keyboard
  // re-show, stray pointer events, the `handleBlur` blur-clears-focused
  // behaviour), the second click must still toggle direction. Using
  // `focused` as the repeat-click signal would fail this test because
  // blur clears it.
  it('toggles direction on second click even if focus was lost between clicks', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    click(inputAt(container, 1, 1)!);
    const target = inputAt(container, 1, 2)!;
    click(target); // direction = down (down-1 starts here)
    expect(wrapAt(container, 2, 2)?.dataset.inWord).toBe('true');
    // Simulate the input losing focus (e.g. user briefly tapped the
    // page chrome, soft keyboard hid + reshowed, etc.).
    act(() => target.blur());
    // Second click on the same cell should still toggle direction.
    act(() => click(target));
    expect(wrapAt(container, 1, 3)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 2, 2)?.dataset.inWord).toBe('false');
  });

  // Pins the behavioral gap: keyboard navigation away from a cell and back
  // must clear lastClickedRef so the next click is treated as a first click
  // (starting-clue preference), not a repeat-click toggle.
  // Without the handleFocus reset: isRepeatClick=true and toggle → 'across'. ✗
  it('first click after keyboard navigation away and back focuses starting clue, not toggle', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    click(inputAt(container, 1, 2)!); // direction = down (down-1 starts here)
    // Arrow away to (2,2) then back to (1,2) — focus moves through handleFocus twice,
    // never through handleClick, so lastClickedRef must be cleared.
    fireEvent.keyDown(inputAt(container, 1, 2)!, { key: 'ArrowDown' });
    fireEvent.keyDown(inputAt(container, 2, 2)!, { key: 'ArrowUp' });
    // direction is still 'down'. Without the fix, toggle fires and switches to 'across'.
    click(inputAt(container, 1, 2)!);
    expect(wrapAt(container, 2, 2)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 3, 2)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 1, 3)?.dataset.inWord).toBe('false');
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

  // Mobile soft keyboards can't trigger the desktop keydown path (key ===
  // "Unidentified"). Without `maxLength={1}`, the browser is allowed to insert
  // the new char even when the cell is full, and `handleInput` truncates
  // `target.value` to just the new letter. This pins that contract — keep
  // `maxLength` off the input.
  it('Android soft-keyboard input replaces an already-filled letter cell', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const cell = inputAt(container, 1, 1)!;
    click(cell);
    // Pre-fill: simulate the user having previously typed "A" here.
    cell.value = 'A';
    // Soft keyboard inserts "b" — without our fix the browser would have
    // blocked the insertion, but the fix lets value transiently become "Ab".
    cell.value = 'Ab';
    cell.dispatchEvent(new InputEvent('input', { inputType: 'insertText', data: 'b', bubbles: true }));
    expect(cell.value).toBe('B');
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

// Tab / Enter cycle across the puzzle's clues in deterministic order:
// by starting (row, col); two clues sharing a start cell (the
// mots-fléchés stacked-clue idiom) have across before down. For
// TEST_PUZZLE this happens to coincide with grouping-by-direction
// because no two clues share a row — see the spatial-interleaving
// test below for a puzzle that exercises the difference.
// Mobile keyboards' "Next" button maps to Enter, so the same handler
// serves the `enterKeyHint="next"` affordance for soft keyboards. Both keys
// wrap at the ends of the list; Shift reverses the direction of travel.
describe('Grid keyboard interactions — Tab / Enter clue cycling', () => {
  it('Tab from a focused cell moves focus to the first cell of the next word', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    // Click (1,1) — first of across-2. orderedClues index = 1.
    const start = inputAt(container, 1, 1)!;
    click(start);
    fireEvent.keyDown(start, { key: 'Tab' });
    // Next clue in order is down-1, starting at (1,2). Direction must
    // flip to 'down' so the new word's cells highlight.
    expect(document.activeElement).toBe(inputAt(container, 1, 2));
    expect(wrapAt(container, 2, 2)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 3, 2)?.dataset.inWord).toBe('true');
    // across-2's perpendicular cells must NOT remain highlighted.
    expect(wrapAt(container, 1, 3)?.dataset.inWord).toBe('false');
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('false');
  });

  it('Tab from the last word wraps to the first word', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    // Click (1,2) — first cell of down-1 (the last clue in orderedClues).
    // Repeat-click logic doesn't apply on the first click; the
    // starting-clue preference picks down-1 → direction='down'.
    const start = inputAt(container, 1, 2)!;
    click(start);
    fireEvent.keyDown(start, { key: 'Tab' });
    // Wraps to across-1, whose only cell is (0,1).
    expect(document.activeElement).toBe(inputAt(container, 0, 1));
  });

  it('Shift+Tab from the first word wraps to the last word', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    // Click (0,1) — across-1. Shift+Tab walks to down-1's start (1,2).
    const start = inputAt(container, 0, 1)!;
    click(start);
    fireEvent.keyDown(start, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(inputAt(container, 1, 2));
    // Direction must be 'down' — verify via the down-1 in-word stripe.
    expect(wrapAt(container, 2, 2)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 3, 2)?.dataset.inWord).toBe('true');
  });

  it('Enter behaves identically to Tab (advances to the next word)', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const start = inputAt(container, 1, 1)!;
    click(start);
    fireEvent.keyDown(start, { key: 'Enter' });
    expect(document.activeElement).toBe(inputAt(container, 1, 2));
  });

  it('Shift+Enter walks backward like Shift+Tab', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    // Click (1,1) [across-2, idx 1]. Shift+Enter steps back to across-1 [idx 0].
    const start = inputAt(container, 1, 1)!;
    click(start);
    fireEvent.keyDown(start, { key: 'Enter', shiftKey: true });
    expect(document.activeElement).toBe(inputAt(container, 0, 1));
  });

  it('Tab when no cell is focused picks the first word\'s first cell', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    // No click — `focused` is still null. Fire Tab on any cell input;
    // the React onKeyDown handler runs regardless of whether the input
    // is the document's activeElement, and the no-focus branch must
    // pick orderedClues[0] = across-1 at (0,1).
    fireEvent.keyDown(inputAt(container, 2, 0)!, { key: 'Tab' });
    expect(document.activeElement).toBe(inputAt(container, 0, 1));
  });

  it('direction flips when Tab moves between an across-word and a down-word', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    // Start at across-1 (idx 0, across). Tab → across-2 (idx 1, still across).
    click(inputAt(container, 0, 1)!);
    fireEvent.keyDown(inputAt(container, 0, 1)!, { key: 'Tab' });
    expect(document.activeElement).toBe(inputAt(container, 1, 1));
    // across-2's stripe should be highlighted (cells (1,2)..(1,4) in word).
    expect(wrapAt(container, 1, 2)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('true');
    // Tab again → down-1 (idx 2). Direction must flip to 'down'.
    fireEvent.keyDown(inputAt(container, 1, 1)!, { key: 'Tab' });
    expect(document.activeElement).toBe(inputAt(container, 1, 2));
    expect(wrapAt(container, 2, 2)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 3, 2)?.dataset.inWord).toBe('true');
    // The across-2 cells perpendicular to (1,2) must not stay lit.
    expect(wrapAt(container, 1, 3)?.dataset.inWord).toBe('false');
  });

  it('Tab calls preventDefault so the browser does not move focus out of the grid', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const start = inputAt(container, 1, 1)!;
    click(start);
    // fireEvent.keyDown returns false when any handler called preventDefault.
    const notDefaulted = fireEvent.keyDown(start, { key: 'Tab' });
    expect(notDefaulted).toBe(false);
  });

  // Regression for the by-direction → by-position sort flip. Three-clue
  // puzzle whose old / new orderings actually diverge:
  //   across-A  start (0,1)  cells (0,1)..(0,3)   from a stacked def at (0,0)
  //   down-1    start (1,0)  cells (1,0),(2,0)    same stacked def
  //   across-B  start (2,2)  cells (2,2)..(2,3)   from def at (2,1)
  // Old ordering (group across then down): A → B → down-1.
  // New ordering (row,col then across-before-down at same start):
  //   A (0,1) → down-1 (1,0) → B (2,2).
  it('cycle interleaves across and down by spatial position, not by direction', () => {
    const SPATIAL: Puzzle = {
      id: 'spatial-test', title: 'spatial test', language: 'fr',
      width: 4, height: 3,
      cells: [
        // Stacked def at (0,0): across at (0,1)..(0,3) and down at (1,0)..(2,0).
        {
          kind: 'definition', position: { row: 0, col: 0 },
          clues: [
            { text: 'A', arrow: 'right' },     // across-A: (0,1)..
            { text: 'D', arrow: 'down' },       // down-1:  (1,0)..
          ],
        },
        { kind: 'letter', position: { row: 0, col: 1 }, entry: '' },
        { kind: 'letter', position: { row: 0, col: 2 }, entry: '' },
        { kind: 'letter', position: { row: 0, col: 3 }, entry: '' },
        { kind: 'letter', position: { row: 1, col: 0 }, entry: '' },
        { kind: 'letter', position: { row: 1, col: 1 }, entry: '' },
        { kind: 'letter', position: { row: 1, col: 2 }, entry: '' },
        { kind: 'letter', position: { row: 1, col: 3 }, entry: '' },
        { kind: 'letter', position: { row: 2, col: 0 }, entry: '' },
        // def at (2,1): across-B at (2,2)..(2,3).
        { kind: 'definition', position: { row: 2, col: 1 }, clues: [{ text: 'B', arrow: 'right' }] },
        { kind: 'letter', position: { row: 2, col: 2 }, entry: '' },
        { kind: 'letter', position: { row: 2, col: 3 }, entry: '' },
      ],
    };
    const { container } = render(<Grid puzzle={SPATIAL} />);
    // Click the first cell of across-A → (0,1).
    const startA = inputAt(container, 0, 1)!;
    click(startA);
    // Tab: by-position cycle expects down-1 next, NOT across-B.
    fireEvent.keyDown(startA, { key: 'Tab' });
    expect(document.activeElement).toBe(inputAt(container, 1, 0));
    // Tab again: across-B.
    fireEvent.keyDown(inputAt(container, 1, 0)!, { key: 'Tab' });
    expect(document.activeElement).toBe(inputAt(container, 2, 2));
    // Tab once more: wraps to across-A.
    fireEvent.keyDown(inputAt(container, 2, 2)!, { key: 'Tab' });
    expect(document.activeElement).toBe(inputAt(container, 0, 1));
  });
});

// onCellChange is the multiplayer hook (Wave H · PR #19) that lets a
// parent broadcast cell writes over the WebSocket without coupling the
// hook to any transport. Solo mode passes nothing and observes no
// behavior change — the existing `Grid keyboard interactions` block
// above is the regression net for that. These tests pin the new
// callback's contract: fires with `(row, col, letter|null)` after the
// uncontrolled <input> has been mutated, exactly once per write site.
describe('Grid keyboard interactions — onCellChange callback', () => {
  it('fires (row, col, "L") when desktop letter typing writes a cell', () => {
    const onCellChange = vi.fn();
    const { container } = render(<Grid puzzle={TEST_PUZZLE} onCellChange={onCellChange} />);
    const start = inputAt(container, 1, 1)!;
    click(start);
    typeChar(start, 'l');
    expect(onCellChange).toHaveBeenCalledTimes(1);
    expect(onCellChange).toHaveBeenCalledWith(1, 1, 'L');
  });

  it('fires (row, col, null) when Backspace clears a filled cell', () => {
    const onCellChange = vi.fn();
    const { container } = render(<Grid puzzle={TEST_PUZZLE} onCellChange={onCellChange} />);
    const target = inputAt(container, 1, 2)!;
    click(target);
    typeChar(target, 'x');           // (1,2,'X')
    click(target);                   // typeChar advanced focus; come back.
    onCellChange.mockClear();
    fireEvent.keyDown(target, { key: 'Backspace' });
    expect(onCellChange).toHaveBeenCalledTimes(1);
    expect(onCellChange).toHaveBeenCalledWith(1, 2, null);
  });

  it('fires (prevRow, prevCol, null) when Backspace on an empty cell clears the previous cell', () => {
    const onCellChange = vi.fn();
    const { container } = render(<Grid puzzle={TEST_PUZZLE} onCellChange={onCellChange} />);
    const first = inputAt(container, 1, 1)!;
    click(first);
    typeChar(first, 'a');            // (1,1,'A'), focus advances to (1,2)
    onCellChange.mockClear();
    const second = inputAt(container, 1, 2)!;
    fireEvent.keyDown(second, { key: 'Backspace' });
    expect(onCellChange).toHaveBeenCalledTimes(1);
    expect(onCellChange).toHaveBeenCalledWith(1, 1, null);
  });

  it('fires (row, col, "L") on Android InputEvent insertText', () => {
    const onCellChange = vi.fn();
    const { container } = render(<Grid puzzle={TEST_PUZZLE} onCellChange={onCellChange} />);
    const start = inputAt(container, 1, 1)!;
    click(start);
    start.value = 'l';
    start.dispatchEvent(new InputEvent('input', { inputType: 'insertText', data: 'l', bubbles: true }));
    expect(onCellChange).toHaveBeenCalledWith(1, 1, 'L');
  });

  it('does not fire when typing the same letter that is already in the cell', () => {
    const onCellChange = vi.fn();
    const { container } = render(<Grid puzzle={TEST_PUZZLE} onCellChange={onCellChange} />);
    const start = inputAt(container, 1, 1)!;
    click(start);
    typeChar(start, 'l');            // (1,1,'L')
    click(start);                    // come back: typeChar advanced focus.
    onCellChange.mockClear();
    typeChar(start, 'l');            // same letter — no change → no callback.
    expect(onCellChange).not.toHaveBeenCalled();
  });

  it('does not fire on Android insertText when typing the same letter already in the cell', () => {
    const onCellChange = vi.fn();
    const { container } = render(<Grid puzzle={TEST_PUZZLE} onCellChange={onCellChange} />);
    const start = inputAt(container, 1, 1)!;
    click(start);
    start.value = 'l';
    start.dispatchEvent(new InputEvent('input', { inputType: 'insertText', data: 'l', bubbles: true }));
    expect(onCellChange).toHaveBeenCalledTimes(1);
    click(start);                    // insertText advanced focus; come back.
    onCellChange.mockClear();
    start.value = 'l';
    start.dispatchEvent(new InputEvent('input', { inputType: 'insertText', data: 'l', bubbles: true }));
    expect(onCellChange).not.toHaveBeenCalled();
  });

  it('does not fire when Backspace on an already-empty first cell has nowhere to walk back', () => {
    const onCellChange = vi.fn();
    const { container } = render(<Grid puzzle={TEST_PUZZLE} onCellChange={onCellChange} />);
    const first = inputAt(container, 1, 1)!;
    click(first);
    fireEvent.keyDown(first, { key: 'Backspace' });
    expect(onCellChange).not.toHaveBeenCalled();
  });

  it('fires (row, col, null) when defensive paste-blanking clears the cell', () => {
    const onCellChange = vi.fn();
    const { container } = render(<Grid puzzle={TEST_PUZZLE} onCellChange={onCellChange} />);
    const cell = inputAt(container, 1, 1)!;
    click(cell);
    cell.value = 'bonjour';
    cell.dispatchEvent(new InputEvent('input', { inputType: 'insertFromPaste', data: null, bubbles: true }));
    expect(cell.value).toBe('');
    expect(onCellChange).toHaveBeenCalledWith(1, 1, null);
  });
});

describe('scheduleVisibleScroll', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers(); });

  it('debounces: only the last cell in a rapid focus burst triggers scrollBy', () => {
    const scrollBy = vi.spyOn(window, 'scrollBy');
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const cell1 = inputAt(container, 1, 1)!;
    const cell2 = inputAt(container, 1, 2)!;
    // Two focus events before the 250 ms window elapses — first timer is
    // cancelled, only the second one fires.
    act(() => { cell1.focus(); });
    act(() => { cell2.focus(); });
    act(() => { vi.advanceTimersByTime(300); });
    expect(scrollBy).toHaveBeenCalledTimes(1);
  });

  it('does not call scrollBy after the component unmounts within the debounce window', () => {
    const scrollBy = vi.spyOn(window, 'scrollBy');
    const { container, unmount } = render(<Grid puzzle={TEST_PUZZLE} />);
    act(() => { inputAt(container, 1, 1)!.focus(); });
    // Unmount clears the pending timeout via the useEffect cleanup.
    act(() => { unmount(); });
    act(() => { vi.advanceTimersByTime(300); });
    expect(scrollBy).not.toHaveBeenCalled();
  });

  it('does not call scrollBy when the focused cell is already in the visible region', () => {
    const scrollBy = vi.spyOn(window, 'scrollBy');
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const cell = inputAt(container, 1, 1)!;
    // jsdom: window.visualViewport is undefined → vvTop=0, vvHeight=innerHeight.
    // SCROLL_TOP_MARGIN_PX=64, SCROLL_BOTTOM_MARGIN_PX=24.
    // rect(top:100, bottom:200) lies within [64, innerHeight-24] → dy===0.
    vi.spyOn(cell, 'getBoundingClientRect').mockReturnValue(
      { top: 100, bottom: 200, left: 0, right: 100, width: 100, height: 100, x: 0, y: 100, toJSON: () => ({}) } as DOMRect,
    );
    act(() => { cell.focus(); });
    act(() => { vi.advanceTimersByTime(300); });
    expect(scrollBy).not.toHaveBeenCalled();
  });

  // Mobile pinch-zoom regression: the auto-scroll exists to dodge the
  // soft keyboard, which keeps `visualViewport.scale === 1`. When the
  // user has explicitly zoomed in (`scale > 1`), firing a `scrollBy`
  // mid-gesture yanks the page back to keep the focused cell in view —
  // user-reported bug. The guard skips the scroll outright.
  it('does not call scrollBy when the user has pinch-zoomed (visualViewport.scale > 1)', () => {
    const scrollBy = vi.spyOn(window, 'scrollBy');
    // jsdom doesn't define `window.visualViewport`; stub one in for the
    // duration of this test. Other tests in this block rely on the
    // default `undefined`, so we restore it in afterEach.
    // CurrentCluePanel's effect attaches a listener to visualViewport,
    // so the stub needs the EventTarget surface in addition to the
    // pinch-zoom values the navigation hook reads.
    Object.defineProperty(window, 'visualViewport', {
      value: {
        scale: 1.5, offsetTop: 0, height: window.innerHeight,
        addEventListener: () => {}, removeEventListener: () => {},
      },
      configurable: true,
    });
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const cell = inputAt(container, 1, 1)!;
    // Force an off-screen rect so the function would otherwise trigger
    // a scroll — the guard is the only thing preventing it.
    vi.spyOn(cell, 'getBoundingClientRect').mockReturnValue(
      { top: -200, bottom: -100, left: 0, right: 100, width: 100, height: 100, x: 0, y: -200, toJSON: () => ({}) } as DOMRect,
    );
    act(() => { cell.focus(); });
    act(() => { vi.advanceTimersByTime(300); });
    expect(scrollBy).not.toHaveBeenCalled();
    // Cleanup the stub so the other tests in this describe see the
    // jsdom default again.
    Object.defineProperty(window, 'visualViewport', { value: undefined, configurable: true });
  });
});
