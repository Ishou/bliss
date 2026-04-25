import { useCallback, useMemo, useRef, useState } from 'react';
import type { Cell, DefinitionCell, LetterCell, Position, Puzzle } from '@/domain';

// 'across' === ArrowDirection 'right'; 'down' === 'down'.
export type Direction = 'across' | 'down';

interface Clue {
  readonly definition: DefinitionCell;
  readonly direction: Direction;
  readonly cells: readonly LetterCell[];
}

export interface CellHighlight {
  readonly currentWord: boolean;
  readonly currentDefinition: boolean;
}

export interface GridNavigation {
  readonly registerCellRef: (el: HTMLInputElement | null) => void;
  readonly highlightFor: (position: Position) => CellHighlight;
  readonly handlePointerDown: (event: React.PointerEvent<HTMLInputElement>) => void;
  readonly handleFocus: (event: React.FocusEvent<HTMLInputElement>) => void;
  readonly handleKeyDown: (event: React.KeyboardEvent<HTMLInputElement>) => void;
}

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
    const dr = def.arrow === 'down' ? 1 : 0, dc = def.arrow === 'right' ? 1 : 0;
    const cells: LetterCell[] = [];
    let row = def.position.row + dr, col = def.position.col + dc;
    while (row >= 0 && row < puzzle.height && col >= 0 && col < puzzle.width) {
      const next = byPos.get(key({ row, col }));
      if (!next || next.kind !== 'letter') break;
      cells.push(next);
      row += dr; col += dc;
    }
    if (cells.length === 0) continue;
    const clue: Clue = { definition: def, direction: def.arrow === 'right' ? 'across' : 'down', cells };
    for (const cell of cells) {
      const k = key(cell.position);
      const list = byCell.get(k) ?? [];
      list.push(clue);
      byCell.set(k, list);
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

  const handlePointerDown = useCallback(
    (event: React.PointerEvent<HTMLInputElement>) => {
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

  const highlightFor = useCallback(
    (p: Position): CellHighlight => {
      if (!currentClue) return { currentWord: false, currentDefinition: false };
      const isFocused = same(focused, p);
      const inWord = currentClue.cells.some((c) => same(c.position, p));
      const def = currentClue.definition.position;
      const isDef = def.row === p.row && def.col === p.col;
      return { currentWord: inWord && !isFocused, currentDefinition: isDef };
    },
    [currentClue, focused],
  );

  return { registerCellRef, highlightFor, handlePointerDown, handleFocus, handleKeyDown };
}
