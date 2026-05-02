import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ArrowDirection, Cell, DefinitionCell, DefinitionClue, LetterCell, Position, Puzzle } from '@/domain';

// 'across' === ArrowDirection 'right'; 'down' === 'down'.
export type Direction = 'across' | 'down';

export interface Clue {
  readonly definition: DefinitionCell;
  readonly clue: DefinitionClue;
  readonly direction: Direction;
  readonly cells: readonly LetterCell[];
}

export interface CellHighlight {
  readonly currentWord: boolean;
  // Arrow direction of the active clue when the focused cell sits on a
  // path that originates at this definition cell; `null` otherwise. The
  // renderer uses this to highlight the matching sub-clue inside a
  // stacked definition cell (ADR-0005 §3a).
  readonly currentArrow: ArrowDirection | null;
}

export interface GridNavigation {
  readonly registerCellRef: (el: HTMLInputElement | null) => void;
  readonly highlightFor: (position: Position) => CellHighlight;
  // click (not pointerdown) — pan gestures never produce a click, so focus is suppressed naturally.
  readonly handleClick: (event: React.MouseEvent<HTMLDivElement>) => void;
  readonly handleFocus: (event: React.FocusEvent<HTMLInputElement>) => void;
  // Clear focused state when an input loses focus (e.g. user taps outside the
  // grid). Without this the word-highlight stripe stayed lit on the last
  // focused row even after the input was visibly blurred. During a typing
  // burst the new cell's focus event fires synchronously after the old
  // cell's blur, and React 18's automatic batching folds the two state
  // updates into one render — no flicker.
  readonly handleBlur: (event: React.FocusEvent<HTMLInputElement>) => void;
  readonly handleKeyDown: (event: React.KeyboardEvent<HTMLInputElement>) => void;
  // Android Gboard fires keydown key==="Unidentified"; the real letter arrives here via InputEvent.data.
  readonly handleInput: (event: React.FormEvent<HTMLInputElement>) => void;
  // Active clue under the focused cell, or null when nothing is focused.
  // Surfaced so the grid container can render the full clue text in a
  // panel outside the grid (cells truncate the prose at small font sizes).
  readonly currentClue: Clue | null;
}

// Divergences from NYT-app behavior:
// - Typing past the last cell of a word stays put (no auto-advance to
//   the next clue). Backspace at the start of a word same — no surprise
//   clue jumps.
// - Tab / Shift+Tab clue-cycling is deferred to a follow-up PR to keep
//   this diff under the ADR-0001 §4 line cap.

const key = (p: Position) => `${p.row},${p.col}`;
const same = (a: Position | null, b: Position | null) =>
  a !== null && b !== null && a.row === b.row && a.col === b.col;
const posOf = (el: HTMLElement): Position | null => {
  const r = el.dataset.row, c = el.dataset.col;
  return r === undefined || c === undefined ? null : { row: Number(r), col: Number(c) };
};
// ASCII A–Z plus French diacritics the puzzle ships with.
const LETTER_RE = /^[a-zA-ZàâçéèêëîïôûùüÿñæœÀÂÇÉÈÊËÎÏÔÛÙÜŸÑÆŒ]$/;

interface ClueLookup {
  cellAt: (r: number, c: number) => Cell | null;
  cluesAt: (r: number, c: number) => readonly Clue[];
  clueAt: (r: number, c: number, d: Direction) => Clue | undefined;
}

// Index built once per puzzle (memo key = puzzle ref). Build O(N + sum
// of clue lengths); lookups O(1).
function buildLookup(puzzle: Puzzle): ClueLookup {
  const byPos = new Map<string, Cell>();
  for (const c of puzzle.cells) byPos.set(key(c.position), c);
  const byCell = new Map<string, Clue[]>();
  const defs = puzzle.cells
    .filter((c): c is DefinitionCell => c.kind === 'definition')
    .slice()
    .sort((a, b) => a.position.row - b.position.row || a.position.col - b.position.col);
  for (const def of defs) {
    // A definition cell carries one or two clues per ADR-0005 §3a.
    // Each clue gets its own answer-path walk and own index entry.
    for (const subClue of def.clues) {
      // Start offset: where the first letter sits relative to the clue.
      // Step: direction letters flow after the first.
      const startDr = (subClue.arrow === 'down' || subClue.arrow === 'down-right') ? 1 : 0;
      const startDc = (subClue.arrow === 'right' || subClue.arrow === 'right-down') ? 1 : 0;
      const dr = (subClue.arrow === 'down' || subClue.arrow === 'right-down') ? 1 : 0;
      const dc = (subClue.arrow === 'right' || subClue.arrow === 'down-right') ? 1 : 0;
      const cells: LetterCell[] = [];
      let row = def.position.row + startDr, col = def.position.col + startDc;
      while (row >= 0 && row < puzzle.height && col >= 0 && col < puzzle.width) {
        const next = byPos.get(key({ row, col }));
        if (!next || next.kind !== 'letter') break;
        cells.push(next);
        row += dr; col += dc;
      }
      if (cells.length === 0) continue;
      const clue: Clue = {
        definition: def,
        clue: subClue,
        direction: (subClue.arrow === 'right' || subClue.arrow === 'down-right') ? 'across' : 'down',
        cells,
      };
      for (const cell of cells) {
        const k = key(cell.position);
        const list = byCell.get(k) ?? [];
        list.push(clue);
        byCell.set(k, list);
      }
    }
  }
  return {
    cellAt: (r, c) => byPos.get(key({ row: r, col: c })) ?? null,
    cluesAt: (r, c) => byCell.get(key({ row: r, col: c })) ?? [],
    clueAt: (r, c, d) => (byCell.get(key({ row: r, col: c })) ?? []).find((q) => q.direction === d),
  };
}

// Scroll-into-visible-viewport timing. We wait for the soft keyboard to
// finish opening before measuring the cell against `window.visualViewport`;
// otherwise the keyboard hasn't shrunk the visual viewport yet and our
// "is the cell visible?" math reads stale dimensions. 250 ms is long
// enough on Android Gboard / iOS Safari (both ~150–250 ms keyboard
// animations) and short enough that the user perceives the scroll as a
// natural reveal rather than a delayed jump. Subsequent focus events
// (typing → auto-advance) cancel the pending scroll, so only the last
// focused cell in a typing burst is considered.
const SCROLL_DELAY_MS = 250;
// Top margin = sticky panel min-height (~2.5rem) + breathing room so the
// cell lands clear of the panel and any focus ring it draws.
const SCROLL_TOP_MARGIN_PX = 64;
// Bottom margin keeps the cell off the visual-viewport bottom edge,
// which sometimes overlaps with the keyboard's accessory bar.
const SCROLL_BOTTOM_MARGIN_PX = 24;

export interface UseGridNavigationOptions {
  // Fires whenever a cell value transitions old → new. `letter` is the
  // post-normalization (uppercased) value or `null` when the cell was
  // cleared. Solo callers omit this; multiplayer callers wire it to the
  // WebSocket cellUpdate broadcast (Wave H · PR #19, see ADR-0018).
  readonly onCellChange?: (row: number, col: number, letter: string | null) => void;
}

export function useGridNavigation(puzzle: Puzzle, options?: UseGridNavigationOptions): GridNavigation {
  const lookup = useMemo(() => buildLookup(puzzle), [puzzle]);
  const refs = useRef(new Map<string, HTMLInputElement>());
  const [focused, setFocused] = useState<Position | null>(null);
  const [direction, setDirection] = useState<Direction>('across');
  // Stash in a ref so existing useCallback closures don't gain a new dep
  // (and so consumers passing an inline function don't churn handlers).
  const onCellChangeRef = useRef(options?.onCellChange);
  onCellChangeRef.current = options?.onCellChange;
  // Tracks the per-cell normalized (uppercase) value so handleInput can
  // detect same-letter no-ops. The browser overwrites target.value with the
  // raw IME character before handleInput fires, making a simple before/after
  // check on target.value unreliable for the Android insertText path.
  const cellValuesRef = useRef(new Map<string, string>());
  // State mirror so stable callbacks see the latest values.
  const stateRef = useRef({ focused, direction });
  stateRef.current = { focused, direction };
  const scrollTimeoutRef = useRef<number | null>(null);
  // Tracks the last cell the user clicked, separately from `focused`.
  // The toggle-on-repeat-click path needs to know whether the *previous
  // click* targeted the same cell — using `focused` would miss the case
  // where the input lost focus between clicks (iOS soft-keyboard quirks,
  // a stray pointer event, the `handleBlur` blur-clears-focused behaviour, etc.).
  // Cleared by `handleFocus` when focus moves to a new cell via keyboard or
  // programmatic navigation, so the next tap is treated as a first click.
  const lastClickedRef = useRef<Position | null>(null);

  // Cancel any pending scroll on unmount so we don't fire scrollBy on a
  // detached input after the puzzle component is gone.
  useEffect(() => () => {
    if (scrollTimeoutRef.current !== null) {
      window.clearTimeout(scrollTimeoutRef.current);
    }
  }, []);

  // Mobile keyboard avoidance. Browsers normally auto-scroll a focused
  // <input> into view when the soft keyboard opens — but that path is
  // broken inside `TransformComponent`'s `overflow: hidden` wrapper:
  // the auto-scroll walks up the DOM looking for a scrollable ancestor,
  // finds the wrapper, "scrolls" it (no-op since it has no overflow),
  // and never reaches the document. We recover by computing where the
  // focused cell sits relative to `window.visualViewport` and calling
  // `window.scrollBy` with the delta needed to bring it inside the
  // visible region (above the keyboard, below the sticky clue panel).
  // This runs *after* a delay so the keyboard has had time to shrink
  // the visual viewport.
  const scheduleVisibleScroll = useCallback((input: HTMLInputElement) => {
    if (typeof window === 'undefined') return;
    if (scrollTimeoutRef.current !== null) {
      window.clearTimeout(scrollTimeoutRef.current);
    }
    scrollTimeoutRef.current = window.setTimeout(() => {
      scrollTimeoutRef.current = null;
      if (!input.isConnected) return;
      const rect = input.getBoundingClientRect();
      const vv = window.visualViewport;
      const vvTop = vv?.offsetTop ?? 0;
      const vvHeight = vv?.height ?? window.innerHeight;
      const visibleTop = vvTop + SCROLL_TOP_MARGIN_PX;
      const visibleBottom = vvTop + vvHeight - SCROLL_BOTTOM_MARGIN_PX;
      let dy = 0;
      // Bottom-overlap (cell behind keyboard) is the common case. Check
      // it first so a cell that sits both below the visible-bottom *and*
      // above the visible-top (a tiny visual viewport) prefers the
      // bottom-shift, which uncovers the cell from the keyboard.
      if (rect.bottom > visibleBottom) dy = rect.bottom - visibleBottom;
      else if (rect.top < visibleTop) dy = rect.top - visibleTop;
      if (dy !== 0) window.scrollBy({ top: dy, behavior: 'smooth' });
    }, SCROLL_DELAY_MS);
  }, []);

  const focusCell = useCallback((p: Position) => {
    refs.current.get(key(p))?.focus();
    setFocused(p);
  }, []);

  const registerCellRef = useCallback((el: HTMLInputElement | null) => {
    if (!el) return;
    const p = posOf(el);
    if (p) refs.current.set(key(p), el);
  }, []);

  const handleClick = useCallback(
    (event: React.MouseEvent<HTMLDivElement>) => {
      const p = posOf(event.currentTarget);
      if (!p) return;
      // Read repeat-click state from `lastClickedRef` (NOT from `focused`):
      // `focused` can be transiently null between two same-cell clicks
      // because of the `handleBlur` blur-clears-focused behaviour + iOS soft-keyboard
      // re-show quirks. Using it here would silently break the NYT-style
      // toggle on real devices even though jsdom tests pass. The click
      // history captures the user's actual interaction sequence regardless
      // of focus churn.
      const isRepeatClick = lastClickedRef.current !== null && same(lastClickedRef.current, p);
      lastClickedRef.current = p;
      const { direction: dir } = stateRef.current;
      const allClues = lookup.cluesAt(p.row, p.col);
      let next = dir;
      if (isRepeatClick) {
        // Same-cell repeat click — NYT-style toggle. Apply across ALL clues
        // at this cell, even if one of them starts here, so the user can
        // still reach the other clue (e.g. tapping the first cell of a
        // down word once focuses the down clue, tapping again toggles to
        // the across clue passing through that same cell).
        if (allClues.length === 1) next = allClues[0].direction;
        else if (allClues.length > 1) {
          const onCurrent = allClues.some((c) => c.direction === dir);
          if (onCurrent) next = dir === 'across' ? 'down' : 'across';
          else next = allClues[0].direction;
        }
      } else {
        // First click on this cell — prefer a clue that STARTS here. When
        // the user taps the first letter of a word, that word's clue takes
        // precedence over the user's previous direction; otherwise typing
        // would pick up mid-answer in an unrelated clue that happens to
        // pass through. Cells with NO starting clue fall back to the
        // existing "pick by current direction / first clue" logic.
        const starting = allClues.filter((c) => same(c.cells[0].position, p));
        const candidates = starting.length > 0 ? starting : allClues;
        if (candidates.length === 1) {
          next = candidates[0].direction;
        } else if (candidates.length > 1) {
          const onCurrent = candidates.some((c) => c.direction === dir);
          next = onCurrent ? dir : candidates[0].direction;
        }
      }
      setDirection(next);
      // Don't call `focusCell` here — the browser focuses the input itself
      // when the click lands on it. Calling `el.focus()` mid-click handler
      // suppresses the soft keyboard on Android Chrome / iOS Safari, which
      // gate the keyboard on the click event reaching its default action
      // intact. `handleFocus` picks up the resulting native focus event
      // and syncs React state.
    },
    [lookup],
  );

  const handleFocus = useCallback((event: React.FocusEvent<HTMLInputElement>) => {
    const p = posOf(event.currentTarget);
    if (p) {
      if (!same(stateRef.current.focused, p)) setFocused(p);
      // Keyboard/programmatic navigation lands here without a preceding handleClick,
      // so lastClickedRef still holds the old cell. Clear it so the next tap on
      // this cell is treated as a first click (starting-clue preference), not a toggle.
      if (!same(lastClickedRef.current, p)) lastClickedRef.current = null;
    }
    scheduleVisibleScroll(event.currentTarget);
  }, [scheduleVisibleScroll]);

  const handleBlur = useCallback(() => {
    // Unconditionally null-out focused. If focus is moving to another
    // letter cell (typing → auto-advance, or tapping a different cell),
    // that cell's `handleFocus` fires synchronously after this and
    // restores `focused` — React batches both updates into one render.
    // If focus is moving outside the grid (user taps the page chrome,
    // hits Tab, etc.) there is no follow-up focus event and the word
    // highlight clears, which is the bug this fixes.
    setFocused(null);
  }, []);

  const currentClue = useMemo<Clue | null>(
    () => (focused ? lookup.clueAt(focused.row, focused.col, direction) ?? null : null),
    [focused, direction, lookup],
  );

  const moveByVector = useCallback(
    (dr: number, dc: number) => {
      const f = stateRef.current.focused;
      if (!f) return;
      let row = f.row + dr, col = f.col + dc;
      while (row >= 0 && row < puzzle.height && col >= 0 && col < puzzle.width) {
        if (lookup.cellAt(row, col)?.kind === 'letter') return focusCell({ row, col });
        row += dr; col += dc;
      }
    },
    [focusCell, lookup, puzzle.height, puzzle.width],
  );

  const handleKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLInputElement>) => {
      const { focused: f, direction: dir } = stateRef.current;
      if (!f) return;
      const k = event.key;
      // Printable letter — write + advance along the current word.
      if (k.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey && LETTER_RE.test(k)) {
        event.preventDefault();
        const el = refs.current.get(key(f));
        const letter = k.toUpperCase();
        if (el) {
          const before = el.value;
          el.value = letter;
          if (before !== letter) {
            cellValuesRef.current.set(key(f), letter);
            onCellChangeRef.current?.(f.row, f.col, letter);
          }
        }
        const clue = lookup.clueAt(f.row, f.col, dir);
        if (!clue) return;
        const idx = clue.cells.findIndex((c) => same(c.position, f));
        if (idx >= 0 && idx < clue.cells.length - 1) focusCell(clue.cells[idx + 1].position);
        return;
      }
      switch (k) {
        case 'ArrowRight':
        case 'ArrowLeft':
          event.preventDefault();
          if (dir !== 'across') setDirection('across');
          else moveByVector(0, k === 'ArrowRight' ? 1 : -1);
          return;
        case 'ArrowDown':
        case 'ArrowUp':
          event.preventDefault();
          if (dir !== 'down') setDirection('down');
          else moveByVector(k === 'ArrowDown' ? 1 : -1, 0);
          return;
        case 'Backspace': {
          event.preventDefault();
          const el = refs.current.get(key(f));
          if (el && el.value !== '') {
            el.value = '';
            cellValuesRef.current.delete(key(f));
            onCellChangeRef.current?.(f.row, f.col, null);
            return;
          }
          const clue = lookup.clueAt(f.row, f.col, dir);
          if (!clue) return;
          const idx = clue.cells.findIndex((c) => same(c.position, f));
          if (idx <= 0) return;
          const prev = clue.cells[idx - 1].position;
          const prevEl = refs.current.get(key(prev));
          if (prevEl) {
            const before = prevEl.value;
            prevEl.value = '';
            if (before !== '') {
              cellValuesRef.current.delete(key(prev));
              onCellChangeRef.current?.(prev.row, prev.col, null);
            }
          }
          focusCell(prev);
          return;
        }
      }
    },
    [focusCell, lookup, moveByVector],
  );

  const handleInput = useCallback(
    (event: React.FormEvent<HTMLInputElement>) => {
      const inputEvent = event.nativeEvent as InputEvent;
      const target = event.currentTarget;
      // insertText = typed character; backspace arrives on keydown with key==="Backspace" reliably.
      if (inputEvent.inputType !== 'insertText') {
        // paste/autocorrect: blank if multi-char or non-letter so cells stay clean.
        if (target.value.length > 1 || (target.value && !LETTER_RE.test(target.value))) {
          target.value = '';
          const p = posOf(target);
          if (p) {
            cellValuesRef.current.delete(key(p));
            onCellChangeRef.current?.(p.row, p.col, null);
          }
        }
        return;
      }
      const data = inputEvent.data;
      if (!data || data.length !== 1 || !LETTER_RE.test(data)) {
        const before = target.value;
        target.value = '';
        if (before !== '') {
          const p = posOf(target);
          if (p) {
            cellValuesRef.current.delete(key(p));
            onCellChangeRef.current?.(p.row, p.col, null);
          }
        }
        return;
      }
      const letter = data.toUpperCase();
      target.value = letter;
      const { focused: f, direction: dir } = stateRef.current;
      if (!f) return;
      const prevLetter = cellValuesRef.current.get(key(f)) ?? '';
      if (prevLetter !== letter) {
        cellValuesRef.current.set(key(f), letter);
        onCellChangeRef.current?.(f.row, f.col, letter);
      }
      const clue = lookup.clueAt(f.row, f.col, dir);
      if (!clue) return;
      const idx = clue.cells.findIndex((c) => same(c.position, f));
      if (idx >= 0 && idx < clue.cells.length - 1) focusCell(clue.cells[idx + 1].position);
    },
    [focusCell, lookup],
  );

  const highlightFor = useCallback(
    (p: Position): CellHighlight => {
      if (!currentClue) return { currentWord: false, currentArrow: null };
      const isFocused = same(focused, p);
      const inWord = currentClue.cells.some((c) => same(c.position, p));
      const def = currentClue.definition.position;
      const isDef = def.row === p.row && def.col === p.col;
      return {
        currentWord: inWord && !isFocused,
        currentArrow: isDef ? currentClue.clue.arrow : null,
      };
    },
    [currentClue, focused],
  );

  return { registerCellRef, highlightFor, handleClick, handleFocus, handleBlur, handleKeyDown, handleInput, currentClue };
}
