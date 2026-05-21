import { render, fireEvent, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { ComponentProps } from 'react';
import type { Cell, Puzzle } from '@/domain';
import { Grid } from '@/ui/components/grid';
import { useGridNavigation } from '@/ui/components/grid/useGridNavigation';

type GestureCallbacks = {
  onZoomStart?: () => void;
  onZoomStop?: () => void;
  onPanningStart?: () => void;
  onPanningStop?: () => void;
};
const capturedCb = vi.hoisted((): { current: GestureCallbacks } => ({ current: {} }));
vi.mock('react-zoom-pan-pinch', async (importActual) => {
  const actual = await importActual<typeof import('react-zoom-pan-pinch')>();
  const ActualTransformWrapper = actual.TransformWrapper;
  type WrapperProps = ComponentProps<typeof ActualTransformWrapper>;
  return {
    ...actual,
    TransformWrapper: ({ children, onZoomStart, onZoomStop, onPanningStart, onPanningStop, ...rest }: WrapperProps) => {
      capturedCb.current = {
        onZoomStart: onZoomStart as (() => void) | undefined,
        onZoomStop: onZoomStop as (() => void) | undefined,
        onPanningStart: onPanningStart as (() => void) | undefined,
        onPanningStop: onPanningStop as (() => void) | undefined,
      };
      return <ActualTransformWrapper {...rest}>{children}</ActualTransformWrapper>;
    },
  };
});

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
const L = (row: number, col: number): Cell =>
  ({ kind: 'letter', position: { row, col }, entry: '' });

const TEST_PUZZLE: Puzzle = {
  id: 'test', title: 'test', language: 'fr', width: 5, height: 4, hintsAllowed: 3, hintsRemaining: 3,
  cells: [
    { kind: 'definition', position: { row: 0, col: 0 }, clues: [{ text: 'across-1', arrow: 'right' }] },
    L(0, 1),
    { kind: 'definition', position: { row: 0, col: 2 }, clues: [{ text: 'down-1', arrow: 'down' }] },
    L(0, 3), L(0, 4),
    { kind: 'definition', position: { row: 1, col: 0 }, clues: [{ text: 'across-2', arrow: 'right' }] },
    L(1, 1), L(1, 2), L(1, 3), L(1, 4),
    L(2, 0), L(2, 1), L(2, 2), L(2, 3), L(2, 4),
    L(3, 0),
    { kind: 'block', position: { row: 3, col: 1 } },
    L(3, 2), L(3, 3), L(3, 4),
  ],
};

// Fixture for smart-focus tests. 5×4 grid laid out so cell (1,3) is the
// middle of an across word (across-B, starting at (1,1)) AND the first
// letter of a down word (down-A). Smart-focus widens the candidate set
// when the prefix of across-B (cells (1,1) and (1,2)) is fully filled.
//
//   D→  X   X   D↓  X      across-A: (0,1),(0,2);  down-A: (1,3),(2,3),(3,3)
//   D→  X   X   X   X      across-B: (1,1),(1,2),(1,3),(1,4)
//   X   X   X   X   X
//   X   X   X   X   X
const SMART_PUZZLE: Puzzle = {
  id: 'smart', title: 'smart', language: 'fr', width: 5, height: 4, hintsAllowed: 3, hintsRemaining: 3,
  cells: [
    { kind: 'definition', position: { row: 0, col: 0 }, clues: [{ text: 'across-A', arrow: 'right' }] },
    L(0, 1), L(0, 2),
    { kind: 'definition', position: { row: 0, col: 3 }, clues: [{ text: 'down-A', arrow: 'down' }] },
    L(0, 4),
    { kind: 'definition', position: { row: 1, col: 0 }, clues: [{ text: 'across-B', arrow: 'right' }] },
    L(1, 1), L(1, 2), L(1, 3), L(1, 4),
    L(2, 0), L(2, 1), L(2, 2), L(2, 3), L(2, 4),
    L(3, 0), L(3, 1), L(3, 2), L(3, 3), L(3, 4),
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

  it('keeps the focused cell and word highlighted after focus leaves the grid', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const target = inputAt(container, 1, 1)!;
    click(target);
    // Pre-blur sanity: the cells along across-2 are highlighted as in-word.
    expect(wrapAt(container, 1, 2)?.dataset.inWord).toBe('true');
    expect(defAt(container, 1, 0)?.dataset.currentClue).toBe('true');
    // Blur the input — simulates the user tapping outside the grid
    // (hint button, page chrome, soft-keyboard transitions). Focus is
    // sticky by design: the player's "active cell" shouldn't disappear
    // every time they tap a non-grid affordance like the hint button,
    // because the next thing they want is for that affordance to act
    // on the cell they were just on. DOM focus also snaps back so
    // typing remains reachable on mobile (soft keyboard up).
    act(() => target.blur());
    expect(wrapAt(container, 1, 2)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('true');
    expect(defAt(container, 1, 0)?.dataset.currentClue).toBe('true');
    expect(document.activeElement).toBe(target);
  });

  it('does not refocus when focus moves to another grid letter cell', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const first = inputAt(container, 1, 1)!;
    const second = inputAt(container, 1, 2)!;
    click(first);
    // Cell-to-cell focus transition (typing auto-advance, arrow-key
    // navigation, repeat-click on a different cell). The refocus
    // heuristic must skip this case so the new cell gets focus, not
    // the previous one. Calling .focus() on `second` triggers blur on
    // `first` with relatedTarget=`second`, exercising the heuristic.
    act(() => second.focus());
    expect(document.activeElement).toBe(second);
  });

  it('does not refocus when focus moves to a text input outside the grid', () => {
    // External text input simulates a search box / form field somewhere
    // else on the page. The user explicitly wants to type there, so the
    // grid must not fight that.
    const external = document.createElement('input');
    external.type = 'text';
    document.body.appendChild(external);
    try {
      const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
      const target = inputAt(container, 1, 1)!;
      click(target);
      act(() => external.focus());
      expect(document.activeElement).toBe(external);
    } finally {
      external.remove();
    }
  });

  it('does not refocus when Tab moves focus to a button outside the grid (WCAG 2.1 SC 2.1.2)', () => {
    // Pressing Tab must be able to reach page controls (hint button, nav
    // buttons, etc.). If handleBlur snapped focus back to the grid cell,
    // keyboard users would be trapped — a WCAG A-level failure.
    const btn = document.createElement('button');
    btn.textContent = 'Hint';
    document.body.appendChild(btn);
    try {
      const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
      const target = inputAt(container, 1, 1)!;
      click(target);
      act(() => btn.focus());
      expect(document.activeElement).toBe(btn);
    } finally {
      btn.remove();
    }
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
  // re-show, stray pointer events), the second click must still toggle
  // direction. `focused` is sticky now and the test still passes against
  // it, but the click history is the load-bearing signal — `focused` can
  // still drift on platforms that yank DOM focus mid-interaction.
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

  // Mots fléchés grids store unaccented uppercase letters; the validation
  // wire form (`normalizeAnswerLetter`) drops diacritics. Players on AZERTY
  // keyboards emit accented glyphs (é, à, ç, …), so the cell write path
  // must collapse those to their base ASCII letter at type time. Otherwise
  // the cell renders 'É' while the validator compares against 'E', and the
  // first letter of every accented word stays visually wrong.
  it('keydown typing strips diacritics from the written letter', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const start = inputAt(container, 1, 1)!;
    click(start);
    typeChar(start, 'é');
    expect(start.value).toBe('E');
    expect(document.activeElement).toBe(inputAt(container, 1, 2));
  });

  it('Android soft-keyboard input strips diacritics from the written letter', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const start = inputAt(container, 1, 1)!;
    click(start);
    start.value = 'à';
    start.dispatchEvent(new InputEvent('input', { inputType: 'insertText', data: 'à', bubbles: true }));
    expect(start.value).toBe('A');
    expect(document.activeElement).toBe(inputAt(container, 1, 2));
  });

  // Mobile soft keyboards (Gboard / iOS) erase via the `input` event with
  // `inputType === 'deleteContentBackward'`, NOT keydown. The browser clears
  // `target.value` *before* handleInput fires, so a before/after diff on
  // `target.value` doesn't detect the deletion. Without reconciling against
  // the cell-values mirror, the cell empties visually but the clue banner
  // (which reads the mirror via `getEntryAt`) keeps showing the stale glyph.
  it('mobile soft-keyboard erase on a filled cell clears the banner letter, not just the DOM input', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const target = inputAt(container, 1, 1)!;
    click(target);
    typeChar(target, 'x');
    click(target); // typeChar advanced focus; come back to (1,1).
    expect(target.value).toBe('X');
    const panel = container.querySelector<HTMLElement>('[data-testid="current-clue-panel"]')!;
    // Sanity: across-2's letter row currently reads "X···" — the typed X is in the banner.
    expect(panel.textContent).toContain('X');
    // Mobile flow: the browser empties the DOM input first, then dispatches
    // `input` with `deleteContentBackward`. By the time handleInput runs,
    // target.value is already ''.
    target.value = '';
    act(() => {
      target.dispatchEvent(new InputEvent('input', { inputType: 'deleteContentBackward', data: null, bubbles: true }));
    });
    expect(target.value).toBe('');
    expect(document.activeElement).toBe(target);
    // The clue banner must reflect the deletion — no stale 'X' in the letter row.
    expect(panel.textContent).not.toContain('X');
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
      width: 4, height: 3, hintsAllowed: 3, hintsRemaining: 3,
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

  it('fires onCellFilled on desktop letter typing (the keydown path, not just the Android insertText path)', () => {
    // Reproduces the user-reported "no network at all when I type" in
    // solo: on desktop, typing a letter goes through `handleKeyDown`'s
    // printable-letter branch, which preventDefaults the event and
    // writes the value manually. The `input` event never fires, so
    // `handleInput` (and the `onCellFilled` it carries) was unreachable
    // on every desktop keystroke. The fix must fire `onCellFilled` from
    // the keydown path too — otherwise auto-validation only fires on
    // Android soft-keyboards.
    const onCellFilled = vi.fn();
    const { container } = render(<Grid puzzle={TEST_PUZZLE} onCellFilled={onCellFilled} />);
    const start = inputAt(container, 1, 1)!;
    click(start);
    typeChar(start, 'l');
    expect(onCellFilled).toHaveBeenCalledTimes(1);
    expect(onCellFilled.mock.calls[0]![0]).toEqual({ row: 1, col: 1 });
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

  it('keeps scrolling when the grid is at its rest scale (1) — keyboard avoidance still works', () => {
    // Sanity sibling for the pinch-zoom guard below: at scale 1 (the
    // steady state in solo mode and in multiplayer when the user has
    // not pinched), the keyboard-avoidance scroll fires unchanged.
    const scrollBy = vi.spyOn(window, 'scrollBy');
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const cell = inputAt(container, 3, 4)!;
    vi.spyOn(cell, 'getBoundingClientRect').mockReturnValue({
      top: window.innerHeight + 50,
      bottom: window.innerHeight + 150,
      left: 0, right: 100, width: 100, height: 100,
      x: 0, y: window.innerHeight + 50, toJSON: () => ({}),
    } as DOMRect);
    act(() => { cell.focus(); });
    act(() => { vi.advanceTimersByTime(300); });
    expect(scrollBy).toHaveBeenCalledTimes(1);
  });

  it('does not call scrollBy while the grid is pinch-zoomed (react-zoom-pan-pinch scale > 1)', () => {
    // Regression for the iOS pinch-zoom-vs-focus bug. The library
    // drives a JS-CSS transform — `window.visualViewport.scale`
    // stays at 1 throughout the user's gesture, so the earlier
    // `vv.scale > 1.01` guard never fired. Drive the library's
    // actual scale to 2 via its public imperative `setTransform`
    // ref API, force the cell off-screen so the un-zoomed code path
    // WOULD scroll, and assert the new guard skips the scroll.
    //
    // Capture the wrapper ref via a small harness — `<Grid>` doesn't
    // expose its internal ref, but we can mount our own
    // `<TransformWrapper>` here, drive its scale, and pass the same
    // `getZoomScale` pattern Grid uses into a direct
    // `useGridNavigation` mount. That tests the hook contract
    // (option `getZoomScale` short-circuits the scroll) without
    // having to reach into Grid's private wiring.
    const scrollBy = vi.spyOn(window, 'scrollBy');
    let resolvedScale = 1;
    const Harness = () => {
      const nav = useGridNavigation(TEST_PUZZLE, {
        getZoomScale: () => resolvedScale,
      });
      return (
        <input
          ref={nav.registerCellRef}
          data-row="3"
          data-col="4"
          data-cell-kind="letter"
          onFocus={nav.handleFocus}
        />
      );
    };
    const { container } = render(<Harness />);
    const input = container.querySelector<HTMLInputElement>('input')!;
    // Force a rect that would otherwise trigger scrollBy.
    vi.spyOn(input, 'getBoundingClientRect').mockReturnValue({
      top: window.innerHeight + 50,
      bottom: window.innerHeight + 150,
      left: 0, right: 100, width: 100, height: 100,
      x: 0, y: window.innerHeight + 50, toJSON: () => ({}),
    } as DOMRect);
    resolvedScale = 2; // simulate post-pinch scale
    act(() => { input.focus(); });
    act(() => { vi.advanceTimersByTime(300); });
    expect(scrollBy).not.toHaveBeenCalled();
  });
});

describe('Grid keyboard-aware shell sizing', () => {
  // Stash & restore visualViewport across tests — jsdom doesn't ship
  // it, so we attach a stub and tear it down explicitly to keep this
  // test from leaking into the others (mirrors PR #175's
  // `onTestFinished` pattern in `current-clue-panel.test.tsx`).
  let originalVV: VisualViewport | undefined;
  let listeners: Map<string, Set<EventListener>>;
  let stub: VisualViewport;

  beforeEach(() => {
    originalVV = window.visualViewport ?? undefined;
    listeners = new Map();
    stub = {
      width: 400,
      height: 800,
      offsetTop: 0,
      offsetLeft: 0,
      pageTop: 0,
      pageLeft: 0,
      scale: 1,
      addEventListener: ((type: string, fn: EventListener) => {
        const set = listeners.get(type) ?? new Set();
        set.add(fn);
        listeners.set(type, set);
      }) as VisualViewport['addEventListener'],
      removeEventListener: ((type: string, fn: EventListener) => {
        listeners.get(type)?.delete(fn);
      }) as VisualViewport['removeEventListener'],
      dispatchEvent: () => true,
      onresize: null,
      onscroll: null,
    } as unknown as VisualViewport;
    Object.defineProperty(window, 'visualViewport', {
      configurable: true, writable: true, value: stub,
    });
  });

  afterEach(() => {
    if (originalVV === undefined) {
      delete (window as { visualViewport?: VisualViewport }).visualViewport;
    } else {
      Object.defineProperty(window, 'visualViewport', {
        configurable: true, writable: true, value: originalVV,
      });
    }
    vi.restoreAllMocks();
  });

  const fire = (type: string) => {
    listeners.get(type)?.forEach((fn) => fn(new Event(type)));
  };

  // The visual-viewport listener now coalesces resize/scroll bursts into
  // one measurement per animation frame. Tests that observe the post-event
  // DOM state need to flush the pending rAF first. jsdom's rAF wraps
  // its callback in a setTimeout(~16 ms) — a 20 ms wait gets us past
  // the rAF tick on every CI runner observed here.
  const flushRaf = () => new Promise((r) => setTimeout(r, 20));

  // Post-PR-#195 fix: the keyboard-avoidance maxHeight is now applied
  // to the flex shell (`[data-testid="grid-shell"]`), NOT the inner
  // TransformWrapper. The inner wrapper is sized via container queries
  // (`min(100cqw, 100cqh, 720px)`) against the shell, so shrinking the
  // shell shrinks the wrapper's square edge automatically — width and
  // height stay locked together when the visualViewport collapses.
  const queryShell = (container: HTMLElement) =>
    container.querySelector<HTMLDivElement>('[data-testid="grid-shell"]');

  it('keeps the shell at its natural flex-grow height when no keyboard is open', async () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const shellEl = queryShell(container);
    expect(shellEl).toBeTruthy();
    // The mount-time synchronous measurement runs before this assertion,
    // so we don't strictly need a flushRaf — but we run one for safety
    // in case the library schedules its own initial transform via rAF.
    await act(async () => { await flushRaf(); });
    // visualViewport.height === window.innerHeight ⇒ no keyboard
    // detected ⇒ no maxHeight override ⇒ inline style.maxHeight is
    // empty (falls through to the panda class which sets none).
    expect(shellEl!.style.maxHeight).toBe('');
  });

  it('shrinks the shell max-height when the soft keyboard reduces visualViewport.height', async () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const shellEl = queryShell(container);
    expect(shellEl).toBeTruthy();
    // Pin a deterministic getBoundingClientRect for the shell so
    // the math doesn't depend on jsdom's default zero-rect.
    vi.spyOn(shellEl as HTMLDivElement, 'getBoundingClientRect').mockReturnValue({
      top: 200, bottom: 600, left: 0, right: 480, width: 480, height: 400,
      x: 0, y: 200, toJSON: () => ({}),
    } as DOMRect);
    // Simulate the keyboard opening: visualViewport.height drops well
    // below window.innerHeight (jsdom default 768).
    (stub as { height: number }).height = 400;
    await act(async () => { fire('resize'); await flushRaf(); });
    // available = (offsetTop + height) - rect.top - WRAPPER_BOTTOM_MARGIN_PX
    //           = (0 + 400) - 200 - 8 = 192
    expect(shellEl!.style.maxHeight).toBe('192px');
  });

  it('clamps the max-height to the floor when available space goes negative (rect.top below keyboard top)', async () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const shellEl = queryShell(container);
    vi.spyOn(shellEl as HTMLDivElement, 'getBoundingClientRect').mockReturnValue({
      top: 700, bottom: 1100, left: 0, right: 480, width: 480, height: 400,
      x: 0, y: 700, toJSON: () => ({}),
    } as DOMRect);
    (stub as { height: number }).height = 400;
    await act(async () => { fire('resize'); await flushRaf(); });
    // available = 400 - 700 - 8 = -308 ⇒ clamped to MIN_WRAPPER_HEIGHT_PX (140).
    expect(shellEl!.style.maxHeight).toBe('140px');
  });

  it('clears the override when the keyboard closes again', async () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const shellEl = queryShell(container);
    vi.spyOn(shellEl as HTMLDivElement, 'getBoundingClientRect').mockReturnValue({
      top: 200, bottom: 600, left: 0, right: 480, width: 480, height: 400,
      x: 0, y: 200, toJSON: () => ({}),
    } as DOMRect);
    (stub as { height: number }).height = 400;
    await act(async () => { fire('resize'); await flushRaf(); });
    expect(shellEl!.style.maxHeight).toBe('192px');
    // Keyboard closes — visualViewport.height returns to layout
    // viewport height. The effect re-runs and clears the override.
    (stub as { height: number }).height = 768; // jsdom default innerHeight
    await act(async () => { fire('resize'); await flushRaf(); });
    expect(shellEl!.style.maxHeight).toBe('');
  });

  it('coalesces multiple visualViewport resize events into a single measurement per frame', async () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const shellEl = queryShell(container);
    expect(shellEl).toBeTruthy();
    const rectSpy = vi.spyOn(shellEl as HTMLDivElement, 'getBoundingClientRect').mockReturnValue({
      top: 200, bottom: 600, left: 0, right: 480, width: 480, height: 400,
      x: 0, y: 200, toJSON: () => ({}),
    } as DOMRect);
    // Keyboard-open precondition so the listener actually runs the
    // rect read (otherwise it bails before the spy).
    (stub as { height: number }).height = 400;
    // Sample baseline immediately before the burst — anything else in
    // the tree that calls `getBoundingClientRect` on the shell gets
    // caught by this spy too, so we count only calls AFTER this point.
    const callsBefore = rectSpy.mock.calls.length;
    // Dispatch 5 resize events in the same synchronous tick — should
    // schedule exactly ONE rAF, not five.
    act(() => {
      fire('resize');
      fire('resize');
      fire('resize');
      fire('resize');
      fire('resize');
    });
    // Without rAF flush, the burst should have produced no new rect
    // reads yet (they're deferred until the rAF fires).
    expect(rectSpy.mock.calls.length).toBe(callsBefore);
    await act(async () => { await flushRaf(); });
    // After the rAF fires, exactly ONE additional call to
    // getBoundingClientRect — proves the 5 events were coalesced.
    expect(rectSpy.mock.calls.length).toBe(callsBefore + 1);
  });

  it('cancels a pending rAF on unmount so the measurement does not run on a torn-down component', async () => {
    const { container, unmount } = render(<Grid puzzle={TEST_PUZZLE} />);
    const shellEl = queryShell(container);
    expect(shellEl).toBeTruthy();
    const rectSpy = vi.spyOn(shellEl as HTMLDivElement, 'getBoundingClientRect').mockReturnValue({
      top: 200, bottom: 600, left: 0, right: 480, width: 480, height: 400,
      x: 0, y: 200, toJSON: () => ({}),
    } as DOMRect);
    (stub as { height: number }).height = 400;
    const callsBefore = rectSpy.mock.calls.length;
    // Schedule the rAF, then unmount before it fires.
    act(() => { fire('resize'); });
    act(() => { unmount(); });
    await flushRaf();
    // No new rect reads — the cancelled rAF didn't run the deferred
    // measurement (which would have setState'd on a torn-down tree
    // and warned in the console).
    expect(rectSpy.mock.calls.length).toBe(callsBefore);
  });

  // Regression test for the second-pass flicker bug (after PR #198 broke
  // FitText's INNER measure-then-resize loop): the OUTER cascade survived.
  // iOS Safari with the keyboard open fires `visualViewport.scroll` every
  // frame and `vv.offsetTop` / `rect.top` jitter sub-pixel values, so each
  // rAF tick computed an `available` that differed from the previous by
  // ≤ 1 px. Without an ε guard, every tick called `setShellMaxHeightPx`
  // ⇒ shell `maxHeight` mutated by 1 px ⇒ container-query units re-resolved
  // ⇒ each cell's ResizeObserver fired with new `cw`/`ch` ⇒ FitText re-ran
  // and on boundary-text cells alternated between two adjacent font sizes —
  // the visible flicker. Mirror of PR #198's pattern up the tree: bail in
  // `commit(...)` when the new value is within ε (1 px) of the last applied.
  it('does not re-write the shell maxHeight on sub-pixel visualViewport jitter (post-#198 outer-loop guard)', async () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const shellEl = queryShell(container);
    expect(shellEl).toBeTruthy();
    // Pin a deterministic rect so `available` math is stable across calls.
    vi.spyOn(shellEl as HTMLDivElement, 'getBoundingClientRect').mockReturnValue({
      top: 200, bottom: 600, left: 0, right: 480, width: 480, height: 400,
      x: 0, y: 200, toJSON: () => ({}),
    } as DOMRect);
    // Open the keyboard and let the first measurement settle the
    // baseline maxHeight (192 px on these stubs).
    (stub as { height: number }).height = 400;
    await act(async () => { fire('resize'); await flushRaf(); });
    expect(shellEl!.style.maxHeight).toBe('192px');

    // Now simulate iOS sub-pixel jitter: each frame, vv.height differs
    // by ≤ 1 px. With the outer-loop guard, the inline maxHeight stays
    // pinned at 192px because the new `available` (192 ± 1) is within
    // ε of the previously committed value.
    const jitter = [400.4, 399.6, 400.2, 399.8, 400.7];
    for (const h of jitter) {
      (stub as { height: number }).height = h;
      await act(async () => { fire('scroll'); await flushRaf(); });
    }
    // The DOM-committed value MUST still be the original 192px — the
    // sub-pixel jitter never propagated to the shell. (Without the
    // guard, the inline value would have ratcheted to '193px' or
    // '191px' and back across the burst, each re-render resizing
    // every cell and re-firing every FitText below.)
    expect(shellEl!.style.maxHeight).toBe('192px');

    // Sanity: a real keyboard close STILL clears the override —
    // ε-guard only suppresses sub-pixel updates, not legitimate
    // changes that exceed the threshold.
    (stub as { height: number }).height = 768;
    await act(async () => { fire('resize'); await flushRaf(); });
    expect(shellEl!.style.maxHeight).toBe('');
  });
});

// Blur-on-gesture coordination. iOS Safari fights pinch / pan
// with a native focus-snap that auto-scrolls / zooms to keep a focused
// <input> visible during ANY layout change. The library drives a JS
// CSS transform during pinch / pan, so the browser's snap fires every
// frame. We mitigate by blurring the focused cell on gesture start and
// restoring focus on gesture end — Android Chrome doesn't do the snap
// as aggressively, but the same blur is a no-op there (and is
// platform-independent, simpler than UA-sniffing).
describe('Grid pan/zoom does not touch focus', () => {
  // Rule: gestures and focus state are independent. A zoom or pan
  // never causes a focus change. The previous blur+restore dance
  // around iOS pinch-zoom was removed because it caused user-visible
  // flicker on desktop.
  it('zoom start does not blur', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const target = inputAt(container, 1, 1)!;
    act(() => { click(target); });
    act(() => { capturedCb.current.onZoomStart!(); });
    expect(document.activeElement).toBe(target);
  });
  it('zoom stop does not restore', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const target = inputAt(container, 1, 1)!;
    act(() => { click(target); });
    act(() => { capturedCb.current.onZoomStart!(); });
    act(() => { capturedCb.current.onZoomStop!(); });
    expect(document.activeElement).toBe(target);
  });
  it('pan start does not blur', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const target = inputAt(container, 1, 1)!;
    act(() => { click(target); });
    act(() => { capturedCb.current.onPanningStart!(); });
    expect(document.activeElement).toBe(target);
  });
  it('pan stop does not restore or blur', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const target = inputAt(container, 1, 1)!;
    act(() => { click(target); });
    act(() => { capturedCb.current.onPanningStart!(); });
    act(() => { capturedCb.current.onPanningStop!(); });
    expect(document.activeElement).toBe(target);
  });
});

describe('Grid keyboard interactions — space-bar direction toggle', () => {
  it('desktop space keydown at an intersection toggles direction', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    // (1,2) is an intersection; click selects down-1 via the starting-clue preference.
    const target = inputAt(container, 1, 2)!;
    click(target);
    expect(wrapAt(container, 2, 2)?.dataset.inWord).toBe('true');
    fireEvent.keyDown(target, { key: ' ' });
    // After the toggle, across-2 is the active word — (1,3)/(1,4) light up.
    expect(wrapAt(container, 1, 3)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 2, 2)?.dataset.inWord).toBe('false');
    expect(wrapAt(container, 3, 2)?.dataset.inWord).toBe('false');
  });

  it('mobile soft-keyboard space input at an intersection toggles direction', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const target = inputAt(container, 1, 2)!;
    click(target);
    expect(wrapAt(container, 2, 2)?.dataset.inWord).toBe('true');
    // Mobile: browser stuffs ' ' into target.value; input event carries data === ' '.
    target.value = ' ';
    act(() => {
      target.dispatchEvent(new InputEvent('input', { inputType: 'insertText', data: ' ', bubbles: true }));
    });
    // Direction toggled to across; (1,3)/(1,4) now in-word.
    expect(wrapAt(container, 1, 3)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 2, 2)?.dataset.inWord).toBe('false');
    // The space must NOT land in the cell — it's a navigation key, not a letter.
    expect(target.value).toBe('');
    // Focus stays on the same cell — direction toggle never moves the caret.
    expect(document.activeElement).toBe(target);
  });

  it('mobile soft-keyboard space input on a single-clue cell is a no-op', () => {
    // (0,1) is on a single clue — toggling direction would strand the user; handler bails.
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const target = inputAt(container, 0, 1)!;
    click(target);
    target.value = ' ';
    act(() => {
      target.dispatchEvent(new InputEvent('input', { inputType: 'insertText', data: ' ', bubbles: true }));
    });
    // Cell stays empty; direction unchanged (still across).
    expect(target.value).toBe('');
    expect(document.activeElement).toBe(target);
  });

  it('mobile soft-keyboard space on a filled cell does not erase the letter', () => {
    // Space-detection runs before the non-letter blanking branch — typed letter survives.
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const target = inputAt(container, 1, 2)!;
    click(target);
    // Type a letter, then come back to the same cell.
    typeChar(target, 'a');
    click(target);
    expect(target.value).toBe('A');
    // Now press space on mobile — typing a space concatenates onto 'A'.
    target.value = 'A ';
    act(() => {
      target.dispatchEvent(new InputEvent('input', { inputType: 'insertText', data: ' ', bubbles: true }));
    });
    // The space is rejected; the letter is preserved.
    expect(target.value).toBe('A');
  });
});

describe('Grid keyboard interactions — validated cells preserve focus', () => {
  it('tapping a validated cell does not move focus off the previously focused mutable cell', () => {
    const validated = new Set<string>(['1,3']);
    const { container } = render(
      <Grid puzzle={TEST_PUZZLE} validatedPositions={validated} />,
    );
    const editable = inputAt(container, 1, 1)!;
    click(editable);
    expect(document.activeElement).toBe(editable);
    // Sanity: the targeted cell really is readOnly.
    const locked = inputAt(container, 1, 3)!;
    expect(locked.readOnly).toBe(true);
    // Tap the wrapper — touch target on mobile (input has pointerEvents: 'none').
    const lockedWrapper = wrapAt(container, 1, 3)!;
    fireEvent.mouseDown(lockedWrapper);
    fireEvent.click(lockedWrapper);
    // Focus must stay on the mutable cell — soft keyboard stays up.
    expect(document.activeElement).toBe(editable);
  });

  it('tapping a validated cell does not change the active word', () => {
    const validated = new Set<string>(['1,2']);
    const { container } = render(
      <Grid puzzle={TEST_PUZZLE} validatedPositions={validated} />,
    );
    // Focus across-2 via (1,1).
    click(inputAt(container, 1, 1)!);
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('true');
    // Tap the validated cell at (1,2) — would otherwise switch to down-1.
    const lockedWrapper = wrapAt(container, 1, 2)!;
    fireEvent.mouseDown(lockedWrapper);
    fireEvent.click(lockedWrapper);
    // across-2 still active — (1,4) still in-word, (2,2) not.
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 2, 2)?.dataset.inWord).toBe('false');
  });
});

// Smart-focus on first click. When the player has filled the prefix of a
// word that passes through the clicked cell, the click should route them
// into THAT word — even if the cell is the structural start of a
// perpendicular word. Without smart-focus, the existing handleClick
// picks the perpendicular real-start, kicking the player off the streak
// they were on. See docs/superpowers/specs/2026-05-17-grid-smart-focus-design.md.
describe('Grid smart-focus on click', () => {
  it('routes click into the clue with a fully filled prefix', () => {
    // Setup: type 'H' 'E' on across-B so cells (1,1) and (1,2) are
    // filled. Auto-advance leaves focus at (1,3) with direction
    // 'across'. Then click (1,3) — which is also the first letter of
    // down-A. Smart-focus must keep direction 'across' because
    // across-B's prefix is fully filled at (1,3).
    const { container } = render(<Grid puzzle={SMART_PUZZLE} />);
    const start = inputAt(container, 1, 1)!;
    click(start);
    typeChar(start, 'h');
    const c12 = inputAt(container, 1, 2)!;
    typeChar(c12, 'e');
    // After two auto-advances, focus is at (1,3). Click it.
    const c13 = inputAt(container, 1, 3)!;
    click(c13);
    // across-B remains current → cells (1,4) along the row are in-word,
    // and down-A's cells (2,3)/(3,3) are NOT in-word.
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 2, 3)?.dataset.inWord).toBe('false');
    expect(wrapAt(container, 3, 3)?.dataset.inWord).toBe('false');
    expect(defAt(container, 1, 0)?.dataset.currentClue).toBe('true');  // across-B
    expect(defAt(container, 0, 3)?.dataset.currentClue).toBe('false'); // down-A
  });

  it('with unfilled prefix, still routes to the perpendicular real-start (regression guard)', () => {
    // No typing — cells (1,1) and (1,2) have no values. across-B at
    // (1,3) sits at idx 2 with two preceding prefix cells, both
    // unfilled → prefixFilled returns false. down-A is the first cell
    // of its word (idx 0 → vacuously filled) → only down-A is a
    // smart-start candidate → switch direction to down.
    const { container } = render(<Grid puzzle={SMART_PUZZLE} />);
    click(inputAt(container, 1, 1)!);  // direction = 'across'
    click(inputAt(container, 1, 3)!);
    expect(wrapAt(container, 2, 3)?.dataset.inWord).toBe('true');  // down-A
    expect(wrapAt(container, 3, 3)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('false'); // not across-B
    expect(defAt(container, 0, 3)?.dataset.currentClue).toBe('true');  // down-A def
    expect(defAt(container, 1, 0)?.dataset.currentClue).toBe('false'); // across-B def
  });

  it('partial prefix does not trigger smart-start', () => {
    // Fill only (1,1), leave (1,2) empty. across-B prefix at (1,3) has
    // a gap → smart-start fails. down-A vacuously smart-start → wins.
    const { container } = render(<Grid puzzle={SMART_PUZZLE} />);
    const c11 = inputAt(container, 1, 1)!;
    click(c11);
    typeChar(c11, 'h');
    // Auto-advance landed focus on (1,2); click (1,3) directly without
    // typing into (1,2).
    click(inputAt(container, 1, 3)!);
    expect(wrapAt(container, 2, 3)?.dataset.inWord).toBe('true');  // down-A wins
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('false');
    expect(defAt(container, 0, 3)?.dataset.currentClue).toBe('true');
    expect(defAt(container, 1, 0)?.dataset.currentClue).toBe('false'); // across-B def not current
  });

  it('filled-prefix smart-start outranks a perpendicular real-start, even when current direction matches the real-start', () => {
    // Reported bug: with HE filled on across-B and direction set to
    // 'down' from working elsewhere, clicking (1,3) used to land on
    // down-A because the global current direction (down) matched the
    // vacuous real-start at (1,3). The fix: real-starts are NOT smart-
    // starts — only clues with at least one filled prefix cell are.
    // So across-B's non-vacuous smart-start outranks down-A's real-
    // start regardless of current direction.
    const { container } = render(<Grid puzzle={SMART_PUZZLE} />);
    click(inputAt(container, 1, 1)!);
    typeChar(inputAt(container, 1, 1)!, 'h');
    typeChar(inputAt(container, 1, 2)!, 'e');
    // Move direction to 'down' via a down-only cell (cell (2,3) — row
    // 2 has no across clue, so only down-A passes through).
    click(inputAt(container, 2, 3)!);
    expect(wrapAt(container, 1, 3)?.dataset.inWord).toBe('true');  // sanity: down-A active
    // Now click (1,3). across-B has filled prefix (smart-start);
    // down-A has only a real-start (vacuous, no longer a smart-start).
    // across-B wins — current direction is overridden.
    click(inputAt(container, 1, 3)!);
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('true');  // across-B
    expect(wrapAt(container, 2, 3)?.dataset.inWord).toBe('false'); // not down-A
    expect(wrapAt(container, 3, 3)?.dataset.inWord).toBe('false');
    expect(defAt(container, 1, 0)?.dataset.currentClue).toBe('true');  // across-B def
    expect(defAt(container, 0, 3)?.dataset.currentClue).toBe('false'); // down-A def
  });
});

describe('Grid letter input — inputMode gated by touch-primary', () => {
  it('renders inputMode="none" on every letter input when touch-primary', () => {
    const original = window.matchMedia;
    window.matchMedia = vi.fn().mockReturnValue({
      matches: true,
      media: '(any-pointer: coarse) and (any-hover: none)',
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => true,
    } as MediaQueryList);
    try {
      const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
      const inputs = container.querySelectorAll<HTMLInputElement>('[data-cell-kind="letter"]');
      expect(inputs.length).toBeGreaterThan(0);
      for (const i of inputs) expect(i.inputMode).toBe('none');
    } finally {
      window.matchMedia = original;
    }
  });

  it('keeps inputMode="text" when not touch-primary', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    const input = container.querySelector<HTMLInputElement>('[data-cell-kind="letter"]')!;
    expect(input.inputMode).toBe('text');
  });
});

describe('Grid hides CurrentCluePanel on touch-primary', () => {
  it('CurrentCluePanel is not rendered on touch-primary; rendered on desktop', () => {
    const original = window.matchMedia;
    const setMatches = (v: boolean) => {
      window.matchMedia = vi.fn().mockReturnValue({
        matches: v,
        media: '(any-pointer: coarse) and (any-hover: none)',
        onchange: null,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
        addListener: () => undefined,
        removeListener: () => undefined,
        dispatchEvent: () => true,
      } as MediaQueryList);
    };
    try {
      setMatches(true);
      const r1 = render(<Grid puzzle={TEST_PUZZLE} />);
      expect(r1.queryByTestId('current-clue-panel')).toBeNull();
      r1.unmount();
      setMatches(false);
      const r2 = render(<Grid puzzle={TEST_PUZZLE} />);
      expect(r2.queryByTestId('current-clue-panel')).toBeTruthy();
    } finally {
      window.matchMedia = original;
    }
  });
});

describe('Grid mounts MobileKeyboard on touch-primary', () => {
  const setTouchPrimary = (val: boolean) => {
    window.matchMedia = vi.fn().mockReturnValue({
      matches: val,
      media: '(any-pointer: coarse) and (any-hover: none)',
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => true,
    } as MediaQueryList);
  };

  it('mounts MobileKeyboard on touch-primary; absent otherwise', () => {
    const original = window.matchMedia;
    try {
      setTouchPrimary(true);
      const r1 = render(<Grid puzzle={TEST_PUZZLE} />);
      expect(r1.queryByRole('group', { name: 'Clavier mots fléchés' })).toBeTruthy();
      r1.unmount();
      setTouchPrimary(false);
      const r2 = render(<Grid puzzle={TEST_PUZZLE} />);
      expect(r2.queryByRole('group', { name: 'Clavier mots fléchés' })).toBeNull();
    } finally {
      window.matchMedia = original;
    }
  });

  it('tapping a keyboard letter writes it into the focused cell', () => {
    const original = window.matchMedia;
    setTouchPrimary(true);
    try {
      const { container, getByLabelText } = render(<Grid puzzle={TEST_PUZZLE} />);
      click(inputAt(container, 1, 1)!);
      fireEvent.click(getByLabelText('Lettre A'));
      expect(inputAt(container, 1, 1)!.value).toBe('A');
      expect(document.activeElement).toBe(inputAt(container, 1, 2));
    } finally {
      window.matchMedia = original;
    }
  });

  it('tapping backspace clears the previous letter and moves focus back', () => {
    const original = window.matchMedia;
    setTouchPrimary(true);
    try {
      const { container, getByLabelText } = render(<Grid puzzle={TEST_PUZZLE} />);
      click(inputAt(container, 1, 1)!);
      fireEvent.click(getByLabelText('Lettre A'));
      fireEvent.click(getByLabelText('Effacer'));
      expect(inputAt(container, 1, 1)!.value).toBe('');
    } finally {
      window.matchMedia = original;
    }
  });

  it('Grid forwards hint props into MobileKeyboard when touch-primary', () => {
    const original = window.matchMedia;
    setTouchPrimary(true);
    try {
      const onRequestHint = vi.fn();
      const getFocusedCell = vi
        .fn()
        .mockReturnValue({ row: 1, column: 1, isLocked: false });
      const { container, getByLabelText } = render(
        <Grid
          puzzle={TEST_PUZZLE}
          hintRemaining={2}
          hintExhausted={false}
          hintPending={false}
          onRequestHint={onRequestHint}
          getFocusedCell={getFocusedCell}
        />,
      );
      click(inputAt(container, 1, 1)!);
      fireEvent.click(getByLabelText(/Demander un indice/));
      expect(onRequestHint).toHaveBeenCalledWith(1, 1);
    } finally {
      window.matchMedia = original;
    }
  });
});
