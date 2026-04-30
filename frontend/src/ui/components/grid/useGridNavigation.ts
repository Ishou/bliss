import { useCallback, useMemo, useRef, useState } from 'react';
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
  // Click (not pointerdown) so we only focus on a confirmed tap. Browsers
  // don't fire `click` after a pan/scroll gesture that started inside the
  // element — switching from onPointerDown removes the "instant focus
  // hijacks the page-pan" behavior on touch devices.
  readonly handleClick: (event: React.MouseEvent<HTMLInputElement>) => void;
  readonly handleFocus: (event: React.FocusEvent<HTMLInputElement>) => void;
  readonly handleKeyDown: (event: React.KeyboardEvent<HTMLInputElement>) => void;
  // Soft-keyboard companion to `handleKeyDown`. Android Gboard / Samsung
  // keyboards fire `keydown` with `key === "Unidentified"` for printable
  // characters — the actual letter only arrives on the `input` event. This
  // handler reads it from `InputEvent.data` and runs the same write +
  // advance logic as the desktop path.
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
//
// Mobile event model:
// - Tap detection: we listen on `click`, not `pointerdown`. Browsers
//   don't fire `click` after a pan gesture that started on the element,
//   so this gives us tap-vs-pan distinction for free without a custom
//   movement-threshold state machine.
// - Letter input: desktop handles letters in `keydown` (preventDefault
//   stops the value mutation, then we set the value + advance manually).
//   Android soft keyboards fire `keydown` with `key === "Unidentified"`
//   so the desktop path doesn't help — `handleInput` reads
//   `InputEvent.data` and runs the same advance logic. The two paths
//   are mutually exclusive: when keydown handles the letter, it
//   preventDefaults so `input` never fires.

const key = (p: Position) => `${p.row},${p.col}`;
const same = (a: Position | null, b: Position | null) =>
  a !== null && b !== null && a.row === b.row && a.col === b.col;
const posOf = (el: HTMLInputElement): Position | null => {
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

export function useGridNavigation(puzzle: Puzzle): GridNavigation {
  const lookup = useMemo(() => buildLookup(puzzle), [puzzle]);
  const refs = useRef(new Map<string, HTMLInputElement>());
  const [focused, setFocused] = useState<Position | null>(null);
  const [direction, setDirection] = useState<Direction>('across');
  // State mirror so stable callbacks see the latest values.
  const stateRef = useRef({ focused, direction });
  stateRef.current = { focused, direction };

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
    (event: React.MouseEvent<HTMLInputElement>) => {
      const p = posOf(event.currentTarget);
      if (!p) return;
      const { focused: prev, direction: dir } = stateRef.current;
      const clues = lookup.cluesAt(p.row, p.col);
      let next = dir;
      if (clues.length === 1) next = clues[0].direction;
      else if (clues.length > 1) {
        const onCurrent = clues.some((c) => c.direction === dir);
        if (same(prev, p) && onCurrent) next = dir === 'across' ? 'down' : 'across';
        else if (!onCurrent) next = clues[0].direction;
      }
      setDirection(next);
      focusCell(p);
    },
    [focusCell, lookup],
  );

  const handleFocus = useCallback((event: React.FocusEvent<HTMLInputElement>) => {
    const p = posOf(event.currentTarget);
    if (p && !same(stateRef.current.focused, p)) setFocused(p);
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
        if (el) el.value = k.toUpperCase();
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
          if (el && el.value !== '') { el.value = ''; return; }
          const clue = lookup.clueAt(f.row, f.col, dir);
          if (!clue) return;
          const idx = clue.cells.findIndex((c) => same(c.position, f));
          if (idx <= 0) return;
          const prev = clue.cells[idx - 1].position;
          const prevEl = refs.current.get(key(prev));
          if (prevEl) prevEl.value = '';
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
      // `inputType` distinguishes a typed character ("insertText") from
      // backspace ("deleteContentBackward") and other edits. We handle
      // only the insert path here; backspace on a soft keyboard still
      // fires `keydown` with `key === "Backspace"` reliably enough that
      // `handleKeyDown` covers it.
      if (inputEvent.inputType !== 'insertText') {
        // Non-insert mutations (paste, autocorrect) are blanked out so
        // the cell never carries multi-char or non-letter content.
        if (target.value.length > 1 || (target.value && !LETTER_RE.test(target.value))) {
          target.value = '';
        }
        return;
      }
      const data = inputEvent.data;
      if (!data || data.length !== 1 || !LETTER_RE.test(data)) {
        target.value = '';
        return;
      }
      target.value = data.toUpperCase();
      const { focused: f, direction: dir } = stateRef.current;
      if (!f) return;
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

  return { registerCellRef, highlightFor, handleClick, handleFocus, handleKeyDown, handleInput, currentClue };
}
