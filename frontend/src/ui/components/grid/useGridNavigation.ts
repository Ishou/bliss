import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ArrowDirection, Cell, DefinitionCell, DefinitionClue, LetterCell, Position, Puzzle } from '@/domain';
import { wordRange } from './wordRange';

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
  // True when this cell is the React-state-focused cell. Drives the
  // solo-mode focused-cell ring on the wrapper so the visual persists
  // when DOM focus leaves the input (e.g. user taps the hint button or
  // page chrome). `false` for all cells whenever no cell is focused.
  readonly focused: boolean;
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
  // Snap DOM focus back to the cell input when blur would otherwise leave
  // the grid for a button / non-focusable element / page background. Keeps
  // typing reachable after a tap on the hint button or page chrome — on
  // mobile this also re-shows the soft keyboard. No React state writes;
  // `focused` is already sticky.
  readonly handleBlur: (event: React.FocusEvent<HTMLInputElement>) => void;
  readonly handleKeyDown: (event: React.KeyboardEvent<HTMLInputElement>) => void;
  // Android Gboard fires keydown key==="Unidentified"; the real letter arrives here via InputEvent.data.
  readonly handleInput: (event: React.FormEvent<HTMLInputElement>) => void;
  // Active clue under the focused cell, or null when nothing is focused.
  // Surfaced so the grid container can render the full clue text in a
  // panel outside the grid (cells truncate the prose at small font sizes).
  readonly currentClue: Clue | null;
  // Position of the focused cell within the active clue's `cells` array
  // (0-based) — the panel renders this as the "2 / 5" progress count.
  // Null whenever `currentClue` is null.
  readonly currentClueIndex: number | null;
  // The OTHER clue at the focused intersection (the one the user could
  // switch to with space / a tap). Null when the cell sits on only one
  // clue path. Drives the alt-clue chip in the current-clue panel
  // (ADR-0005 §6 redesign — cell-at-intersection state).
  readonly alternateClue: Clue | null;
  // Flips the active solving direction at the focused cell. Surfaced
  // for the panel's alt-clue button — clicking it should switch
  // direction without touching focus, same effect as a re-tap on an
  // intersection cell.
  readonly toggleDirection: () => void;
  // Reads the player's current letter at (row, col) — '' when the cell
  // is empty. The callback identity changes whenever any letter
  // changes (per the version counter inside the hook), so React
  // re-renders consumers of the panel's letter previews on every
  // typed / cleared character.
  readonly getEntryAt: (row: number, col: number) => string;
  // The local user's current cursor — `null` when nothing is focused.
  // Mirrors the data the outbound `onLocalFocusChange` callback already
  // emits, but synchronously available for local rendering (presence map
  // composition, the multiplayer cursor highlight pipeline, etc).
  readonly localCursor: {
    readonly position: Position;
    readonly direction: Direction;
  } | null;
  // Inbound multiplayer write — apply a remote `cellUpdated` event to the
  // uncontrolled <input> at (row, col). Writes `letter ?? ''` directly to
  // `el.value` (per ADR-0002 §4: cell values live in the DOM), updates
  // the same per-cell mirror that handleInput/handleKeyDown use so a
  // subsequent local same-letter no-op is detected, and DOES NOT fire
  // `onCellChange` (the inbound path must never re-emit and create an
  // echo loop). No focus or direction change — remote writes by other
  // players must not yank the local user's caret. Used by `Grid` (Wave H
  // PR #19) when wired to the WebSocket cellUpdated stream. No-op if the
  // target cell is not a registered letter input (out-of-bounds writes
  // from a stale broadcast are ignored).
  readonly applyRemoteCellUpdate: (row: number, col: number, letter: string | null) => void;
}

// Divergences from NYT-app behavior:
// - Typing past the last cell of a word stays put (no auto-advance to
//   the next clue). Backspace at the start of a word same — no surprise
//   clue jumps.
// - Tab / Enter cycle to the NEXT word's first cell (Shift+Tab /
//   Shift+Enter walk backward); both wrap. The clue order is
//   across-then-down, sub-sorted by the starting cell's (row, col) — so
//   the cycle traverses every across answer top-to-bottom, then every
//   down answer top-to-bottom, then wraps. Mobile keyboards' "Next"
//   button maps to Enter, so the same path serves the
//   `enterKeyHint="next"` affordance for soft keyboards.

const key = (p: Position) => `${p.row},${p.col}`;
const same = (a: Position | null, b: Position | null) =>
  a !== null && b !== null && a.row === b.row && a.col === b.col;
const posOf = (el: HTMLElement): Position | null => {
  const r = el.dataset.row, c = el.dataset.col;
  return r === undefined || c === undefined ? null : { row: Number(r), col: Number(c) };
};
// ASCII A–Z plus French diacritics the puzzle ships with.
const LETTER_RE = /^[a-zA-ZàâçéèêëîïôûùüÿñæœÀÂÇÉÈÊËÎÏÔÛÙÜŸÑÆŒ]$/;

// Display-form normalization for a single accepted keystroke. Strips
// combining diacritics (NFD then drops U+0300–U+036F) and uppercases
// — so 'é' renders as 'E', 'ç' as 'C', etc. Mots fléchés grids are
// stored unaccented (matches the wire form `normalizeAnswerLetter`
// produces for validation), and players type with their native AZERTY
// keyboard which emits accented glyphs. Ligatures (Æ/Œ) don't decompose
// under NFD and are preserved as-is — that's a pre-existing latent
// case the validation layer already rejects, kept untouched here.
const stripDiacritics = (letter: string): string =>
  letter.toUpperCase().normalize('NFD').replace(/[̀-ͯ]/g, '');

interface ClueLookup {
  cellAt: (r: number, c: number) => Cell | null;
  cluesAt: (r: number, c: number) => readonly Clue[];
  clueAt: (r: number, c: number, d: Direction) => Clue | undefined;
  // All clues in deterministic puzzle order: across-then-down, each
  // group sub-sorted by (row, col) of the starting cell. This is the
  // order Tab / Enter walks through. Built once per puzzle.
  readonly orderedClues: readonly Clue[];
}

// Index built once per puzzle (memo key = puzzle ref). Build O(N + sum
// of clue lengths); lookups O(1).
function buildLookup(puzzle: Puzzle): ClueLookup {
  const byPos = new Map<string, Cell>();
  for (const c of puzzle.cells) byPos.set(key(c.position), c);
  const byCell = new Map<string, Clue[]>();
  const allClues: Clue[] = [];
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
      allClues.push(clue);
    }
  }
  // Deterministic ordering for Tab / Enter cycling: by starting cell in
  // row-major order (row, then col). Across before down as a stable
  // tiebreak when two clues share a start cell (unusual in standard
  // grid geometry, but a safe default).
  const orderedClues: readonly Clue[] = allClues.slice().sort((a, b) => {
    const ar = a.cells[0].position, br = b.cells[0].position;
    if (ar.row !== br.row) return ar.row - br.row;
    if (ar.col !== br.col) return ar.col - br.col;
    if (a.direction !== b.direction) return a.direction === 'across' ? -1 : 1;
    return 0;
  });
  return {
    cellAt: (r, c) => byPos.get(key({ row: r, col: c })) ?? null,
    cluesAt: (r, c) => byCell.get(key({ row: r, col: c })) ?? [],
    clueAt: (r, c, d) => (byCell.get(key({ row: r, col: c })) ?? []).find((q) => q.direction === d),
    orderedClues,
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
  // Fires after a letter is written into a cell (post-normalization).
  // Solo's auto-validation hook reads this to detect "word just completed
  // its last letter", call POST /v1/puzzles/:id/validate, and lock the
  // word's cells if every letter was correct. Multiplayer callers omit
  // this — the server drives validation and broadcasts `wordLocked`. Not
  // fired on cell clears (auto-validation has nothing to do with deletes).
  readonly onCellFilled?: (
    position: Position,
    direction: 'across' | 'down',
  ) => void;
  // Fires whenever the focused cell or solving direction changes. The
  // multiplayer route wires this to `gameClient.cellFocus(...)` so peers
  // can render a coloured cursor + word tint at the focused position
  // (ADR-0018 §"Presence"). Adapter-side debounce (200 ms) collapses
  // bursts of focus changes into one outbound frame, so we fire on
  // every transition without filtering. Solo callers omit this and the
  // hook never schedules an out-of-band call.
  readonly onFocusChange?: (
    position: Position | null,
    direction: 'across' | 'down' | null,
  ) => void;
  // Returns the current zoom scale of the grid's `react-zoom-pan-pinch`
  // wrapper (1 = unzoomed). When `> 1.01`, `scheduleVisibleScroll`
  // bails out: a zoomed-in user has explicitly taken control of the
  // viewport and any auto-scroll-into-view fights the gesture. Read
  // from `transformWrapperRef.current?.state.scale` at the call site —
  // we accept a getter (not a value) because the library mutates its
  // state object in place, so a snapshot taken at hook-options time
  // would be stale. Solo callers wired without zoom can omit this and
  // the guard becomes a no-op (scale defaults to 1). Note: an earlier
  // attempt (PR #175) used `window.visualViewport.scale` instead, but
  // the library drives a JS-CSS transform — `visualViewport.scale`
  // stays at 1 throughout pinch, so the guard never fired.
  readonly getZoomScale?: () => number;
  // Returns `true` while a pan gesture is in progress (or has just
  // ended within the post-mouseup grace window). When set, both
  // `handleClick` and `handleFocus` early-return — pan is the user's
  // gesture intent, not a slot-selection. Without this guard, the
  // browser's focus-on-mousedown + click-after-mouseup events would
  // fire `setFocused` and `setDirection` for the wrong cell, leaving
  // the highlighted word out of sync with the actual focused cell.
  // Read as a getter (not a value) so the gesture state stays
  // synchronous; useGridNavigation re-evaluates on each event.
  readonly isPanning?: () => boolean;
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
  // Same ref pattern as `onCellChange` so an inline arrow at the call
  // site doesn't churn handler identities below.
  const onCellFilledRef = useRef(options?.onCellFilled);
  onCellFilledRef.current = options?.onCellFilled;
  // Same pattern for the presence-focus callback: refs keep handlers
  // stable so consumers passing inline functions don't trigger a fresh
  // mounting effect every render.
  const onFocusChangeRef = useRef(options?.onFocusChange);
  onFocusChangeRef.current = options?.onFocusChange;
  // Zoom-scale getter. Stashed in a ref so callers passing an inline
  // arrow (e.g. `() => wrapperRef.current?.state.scale ?? 1`) don't
  // churn `scheduleVisibleScroll`'s identity on every render.
  const getZoomScaleRef = useRef(options?.getZoomScale);
  getZoomScaleRef.current = options?.getZoomScale;
  // Pan-state getter. Stashed in a ref so consumers passing an inline
  // arrow don't churn the handler identities below.
  const isPanningRef = useRef(options?.isPanning);
  isPanningRef.current = options?.isPanning;
  // Tracks the per-cell normalized (uppercase) value so handleInput can
  // detect same-letter no-ops. The browser overwrites target.value with the
  // raw IME character before handleInput fires, making a simple before/after
  // check on target.value unreliable for the Android insertText path.
  const cellValuesRef = useRef(new Map<string, string>());
  // `entriesVersion` is bumped on every write to `cellValuesRef`. The
  // hook's `getEntryAt` selector is memoized on the version so consumers
  // (the current-clue panel's letter previews) re-render when typed
  // letters change. Without this seam typed values would be stale on
  // the panel until the next focus event — fine for auto-advance words
  // but visibly broken on the last cell of a clue where focus stays.
  const [entriesVersion, setEntriesVersion] = useState(0);
  const bumpEntries = useCallback(() => setEntriesVersion((v) => v + 1), []);
  // State mirror so stable callbacks see the latest values.
  const stateRef = useRef({ focused, direction });
  stateRef.current = { focused, direction };
  const scrollTimeoutRef = useRef<number | null>(null);
  // Tracks the last cell the user clicked, separately from `focused`.
  // The toggle-on-repeat-click path needs to know whether the *previous
  // click* targeted the same cell — `focused` is sticky now, but iOS
  // soft-keyboard hide/reshow and stray pointer events can still
  // interleave focus changes between two same-cell clicks, so the
  // click history captures the user's actual interaction sequence
  // independent of focus churn. Cleared by `handleFocus` when focus
  // moves to a new cell via keyboard or programmatic navigation, so
  // the next tap is treated as a first click.
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
      // Skip the auto-scroll-into-view when the grid is pinch-zoomed.
      // The user has explicitly taken control of the in-grid viewport
      // (the library transforms the grid's content via JS-driven CSS
      // transform); auto-scrolling now yanks the page out from under
      // the cell they just zoomed to inspect. 1.01 (not strict 1.0)
      // absorbs floating-point jitter — `react-zoom-pan-pinch` has
      // been observed to leave `state.scale` at 1.0000000002 after
      // bounds-snap. Keyboard-avoidance (the original reason this
      // helper exists) is preserved: at scale === 1 (the steady state
      // unless the user has actively pinched in) the guard is a no-op.
      const zoomScale = getZoomScaleRef.current?.() ?? 1;
      if (zoomScale > 1.01) return;
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
      // A click event arriving as the tail of a pan gesture isn't a
      // slot-selection. Bailing here keeps `direction` and
      // `lastClickedRef` exactly as they were before the gesture.
      if (isPanningRef.current?.() === true) return;
      const p = posOf(event.currentTarget);
      if (!p) return;
      // Read repeat-click state from `lastClickedRef` (NOT from `focused`):
      // iOS soft-keyboard hide/reshow can interleave focus changes
      // between two same-cell clicks even with sticky `focused`. The
      // click history captures the user's actual interaction sequence
      // independent of any focus churn that platform quirks introduce.
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
      // Focus the cell's input. The cell wrapper's mousedown handler
      // calls `event.preventDefault()` to stop the browser's default
      // blur of the active element, which also stops the default focus
      // path — so the input would never gain focus without this call.
      // Doing it here (and not on mousedown) means the focus only
      // moves on a real click (no drag), and the isPanning gate above
      // covers the synthesised post-pan click.
      refs.current.get(key(p))?.focus();
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

  // Focus is intentionally sticky: tapping outside the grid leaves
  // `focused` set so highlight + clue panel + presence cursor persist.
  // A click on another letter cell still moves `focused` via that
  // cell's `handleFocus`. Grid is remounted on puzzle change
  // (key=refreshCount) so stale focus never leaks across puzzles.
  //
  // DOM focus is a separate concern: when the user taps a button, the
  // cell input itself blurs, which on mobile hides the soft keyboard
  // and on every platform makes typing unreachable. Snap DOM focus
  // back unless the blur is moving somewhere the user actually wants
  // it (another grid cell, another text input, a link). Synchronous
  // call so iOS Safari keeps us in the user-gesture context required
  // to re-show the soft keyboard.
  const handleBlur = useCallback((event: React.FocusEvent<HTMLInputElement>) => {
    if (!document.hasFocus()) return;
    const next = event.relatedTarget;
    if (next instanceof HTMLElement) {
      if (next.closest('[data-cell-kind="letter"]')) return;
      if (next instanceof HTMLInputElement) {
        const t = next.type;
        if (t === 'text' || t === 'search' || t === 'email' || t === 'password' || t === 'tel' || t === 'url' || t === 'number') return;
      }
      if (next instanceof HTMLTextAreaElement) return;
      if (next instanceof HTMLAnchorElement && next.href) return;
      if (next instanceof HTMLButtonElement) return;
      if (next instanceof HTMLSelectElement) return;
      if (next.isContentEditable) return;
    }
    event.currentTarget.focus({ preventScroll: true });
  }, []);

  const currentClue = useMemo<Clue | null>(
    () => (focused ? lookup.clueAt(focused.row, focused.col, direction) ?? null : null),
    [focused, direction, lookup],
  );

  // Cell-within-word index for the panel's "X / N" progress count.
  const currentClueIndex = useMemo<number | null>(() => {
    if (!focused || !currentClue) return null;
    const idx = currentClue.cells.findIndex((c) => same(c.position, focused));
    return idx >= 0 ? idx : null;
  }, [focused, currentClue]);

  // Alternate-direction clue at the same intersection, when present.
  // The lookup is direction-keyed so we just flip and re-query — same
  // path the keyboard space-bar / re-tap direction toggle takes.
  const alternateClue = useMemo<Clue | null>(() => {
    if (!focused) return null;
    const altDirection: Direction = direction === 'across' ? 'down' : 'across';
    return lookup.clueAt(focused.row, focused.col, altDirection) ?? null;
  }, [focused, direction, lookup]);

  const toggleDirection = useCallback(() => {
    setDirection((prev) => (prev === 'across' ? 'down' : 'across'));
  }, []);

  const getEntryAt = useCallback(
    (row: number, col: number): string =>
      cellValuesRef.current.get(key({ row, col })) ?? '',
    // The version counter is the load-bearing dep — it shifts on every
    // write to `cellValuesRef`, so the callback identity changes and
    // memo'd consumers (the clue panel) re-render with fresh letters.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [entriesVersion],
  );

  // Fire the presence-focus callback on every (focused, direction)
  // transition. The transport-layer adapter (`WebSocketGameClient`) is
  // the single source of truth for the 200 ms debounce — this hook
  // surfaces every change verbatim. `null` focused fires `(null, null)`
  // so peers drop the cursor; that's intentional and matches the wire
  // shape (`presenceUpdated.row` is nullable per AsyncAPI). When
  // `focused` is non-null we always have a meaningful direction.
  useEffect(() => {
    const handler = onFocusChangeRef.current;
    if (!handler) return;
    if (focused === null) {
      handler(null, null);
      return;
    }
    handler(focused, direction);
  }, [focused, direction]);

  // Word range under the focused cell along the active direction. Pure
  // helper, same one PresenceOverlay uses to render remote cursors —
  // so the local "current word" tint and the remote-presence tints
  // stay byte-identical for any (position, direction) pair.
  const currentWordRange = useMemo<readonly Position[]>(
    () => (focused ? wordRange(puzzle, focused, direction) : []),
    [focused, direction, puzzle],
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
      const k = event.key;
      // Tab / Enter — cycle to the next (or, with Shift, previous) word's
      // first cell. Handled BEFORE the printable-letter branch so Enter
      // never falls through to letter-write logic (it isn't a letter, but
      // belt-and-braces) and BEFORE the `if (!f) return` guard so a Tab /
      // Enter with no current focus picks the first word's first cell.
      // preventDefault on Tab stops the browser walking out of the grid;
      // on Enter it stops form-submit-style side effects on hosts where
      // this hook is embedded in a form.
      if (k === 'Tab' || k === 'Enter') {
        event.preventDefault();
        const clues = lookup.orderedClues;
        if (clues.length === 0) return;
        let nextClue: Clue;
        if (!f) {
          // No current focus — Shift jumps to the last word, plain to the
          // first. Keeps the keyboard-first user from getting stuck when
          // they Tab into the grid before clicking any cell.
          nextClue = event.shiftKey ? clues[clues.length - 1] : clues[0];
        } else {
          // Locate the current word. Prefer the clue matching the active
          // direction (the same rule `currentClue` uses); fall back to any
          // clue passing through the focused cell so a stale `direction`
          // doesn't strand the cycle. If no clue covers the focused cell
          // at all (defensive — shouldn't happen for letter cells), step
          // from "before the first / after the last" so the wrap math
          // still lands somewhere sensible.
          const here = lookup.cluesAt(f.row, f.col);
          const current = here.find((h) => h.direction === dir) ?? here[0];
          const currentIdx = current ? clues.indexOf(current) : -1;
          const step = event.shiftKey ? -1 : 1;
          const baseIdx = currentIdx < 0 ? (event.shiftKey ? 0 : -1) : currentIdx;
          // Modulo-with-wrap (works for negative steps too).
          const nextIdx = (baseIdx + step + clues.length) % clues.length;
          nextClue = clues[nextIdx];
        }
        // Direction first so the new cell's `currentClue` resolves to the
        // word we just jumped TO, not the perpendicular clue that may
        // also pass through its starting cell. React 18 batches the
        // setDirection + setFocused inside this handler into one render.
        if (nextClue.direction !== dir) setDirection(nextClue.direction);
        focusCell(nextClue.cells[0].position);
        return;
      }
      if (!f) return;
      // Printable letter — write + advance along the current word.
      if (k.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey && LETTER_RE.test(k)) {
        event.preventDefault();
        const el = refs.current.get(key(f));
        const letter = stripDiacritics(k);
        if (el) {
          // Validated cells render with `readOnly` (see LetterCellView)
          // — typing should not overwrite a locked-in correct letter.
          // The browser already swallows native input on readOnly, but
          // this manual write path runs from `keydown`, so we have to
          // gate it explicitly. We still advance focus so the player's
          // typing flow continues across a partially-solved word.
          if (!el.readOnly) {
            const before = el.value;
            el.value = letter;
            if (before !== letter) {
              cellValuesRef.current.set(key(f), letter);
              bumpEntries();
              onCellChangeRef.current?.(f.row, f.col, letter);
            }
            // The `input` event never fires on this branch (we
            // preventDefault'd the keydown), so handleInput's
            // `onCellFilled` path is unreachable from desktop typing.
            // Fire it here so solo auto-validation runs on every
            // keystroke regardless of platform — the Android soft-
            // keyboard path covers the same callback in handleInput.
            if (!el.readOnly) onCellFilledRef.current?.(f, dir);
          }
        }
        const clue = lookup.clueAt(f.row, f.col, dir);
        if (!clue) return;
        const idx = clue.cells.findIndex((c) => same(c.position, f));
        if (idx >= 0 && idx < clue.cells.length - 1) focusCell(clue.cells[idx + 1].position);
        return;
      }
      switch (k) {
        // Space — flip solving direction at the current intersection
        // (matches NYT crossword + most French puzzle apps; the panel's
        // "Espace" kbd chip exposes the same shortcut visually).
        case ' ':
        case 'Spacebar': {
          event.preventDefault();
          const here = lookup.cluesAt(f.row, f.col);
          if (here.length < 2) return;
          setDirection((prev) => (prev === 'across' ? 'down' : 'across'));
          return;
        }
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
          // Validated cells are locked — Backspace must not erase a
          // correct letter. Still walk back through the word so a
          // player who's typing forward and overshoots can recover
          // by chaining backspaces (the previous editable cell will
          // get cleared, and focus lands there).
          if (el && el.value !== '' && !el.readOnly) {
            el.value = '';
            cellValuesRef.current.delete(key(f));
            bumpEntries();
            onCellChangeRef.current?.(f.row, f.col, null);
            return;
          }
          const clue = lookup.clueAt(f.row, f.col, dir);
          if (!clue) return;
          const idx = clue.cells.findIndex((c) => same(c.position, f));
          if (idx <= 0) return;
          const prev = clue.cells[idx - 1].position;
          const prevEl = refs.current.get(key(prev));
          if (prevEl && !prevEl.readOnly) {
            const before = prevEl.value;
            prevEl.value = '';
            if (before !== '') {
              cellValuesRef.current.delete(key(prev));
              bumpEntries();
              onCellChangeRef.current?.(prev.row, prev.col, null);
            }
          }
          focusCell(prev);
          return;
        }
      }
    },
    [bumpEntries, focusCell, lookup, moveByVector],
  );

  const handleInput = useCallback(
    (event: React.FormEvent<HTMLInputElement>) => {
      const inputEvent = event.nativeEvent as InputEvent;
      const target = event.currentTarget;
      // Validated cells are read-only. Browsers block native input
      // here, but Android soft keyboards have been observed to bypass
      // readOnly via composition events — mirror the keydown path's
      // guard so a typed letter never lands on a locked cell.
      if (target.readOnly) return;
      // Non-insert input events split two ways:
      //   1. Paste / autocorrect — multi-char or non-letter content lands
      //      in `target.value`. Blank it and notify (always — even if the
      //      mirror was already empty, consumers may need to know a paste
      //      was rejected because server-side state could differ).
      //   2. Mobile erase — Gboard / iOS fire `deleteContentBackward` here
      //      when erasing a filled cell. The browser already cleared the
      //      DOM by the time we run, so we can't diff `target.value`.
      //      Reconcile against the cell-values mirror instead.
      // Desktop Backspace doesn't reach here; handleKeyDown's
      // `case 'Backspace'` preventDefaults and keeps the mirror in sync
      // itself. The empty-cell mobile case still arrives via keydown
      // (browser has nothing to delete natively, so it falls through).
      if (inputEvent.inputType !== 'insertText') {
        const p = posOf(target);
        if (target.value.length > 1 || (target.value && !LETTER_RE.test(target.value))) {
          target.value = '';
          if (p) {
            cellValuesRef.current.delete(key(p));
            bumpEntries();
            onCellChangeRef.current?.(p.row, p.col, null);
          }
          return;
        }
        if (target.value === '' && p && cellValuesRef.current.has(key(p))) {
          cellValuesRef.current.delete(key(p));
          bumpEntries();
          onCellChangeRef.current?.(p.row, p.col, null);
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
            bumpEntries();
            onCellChangeRef.current?.(p.row, p.col, null);
          }
        }
        return;
      }
      const letter = stripDiacritics(data);
      target.value = letter;
      // Identify the destination cell from the input event's target —
      // the element that actually received the keystroke — rather than
      // from React's `focused` state. Focus state can lag during a fast
      // typing burst (auto-advance queues a setFocused that hasn't
      // committed yet by the time the next input event fires), and the
      // target is the authoritative source for "which cell was typed
      // into".
      const p = posOf(target);
      if (!p) return;
      const { direction: dir } = stateRef.current;
      const prevLetter = cellValuesRef.current.get(key(p)) ?? '';
      if (prevLetter !== letter) {
        cellValuesRef.current.set(key(p), letter);
        bumpEntries();
        onCellChangeRef.current?.(p.row, p.col, letter);
      }
      // Auto-validation seam: announce that a letter was placed at
      // (p, dir). The hook's consumer (solo route's
      // `useWordAutoValidation`) decides whether the fill closed a word.
      // Fired on every keystroke that lands a letter, even when the
      // letter was already the same — re-typing the last letter of a
      // correct word should still trigger the lock check.
      onCellFilledRef.current?.(p, dir);
      const clue = lookup.clueAt(p.row, p.col, dir);
      if (!clue) return;
      const idx = clue.cells.findIndex((c) => same(c.position, p));
      if (idx >= 0 && idx < clue.cells.length - 1) focusCell(clue.cells[idx + 1].position);
    },
    [bumpEntries, focusCell, lookup],
  );

  const highlightFor = useCallback(
    (p: Position): CellHighlight => {
      if (!focused || !currentClue) return { currentWord: false, focused: false, currentArrow: null };
      const isFocused = same(focused, p);
      // `currentWordRange` is computed via the same `wordRange` helper
      // PresenceOverlay uses for remote cursors — both code paths now
      // see the same range for any (position, direction) pair.
      const inWord = currentWordRange.some((q) => q.row === p.row && q.col === p.col);
      const def = currentClue.definition.position;
      const isDef = def.row === p.row && def.col === p.col;
      return {
        currentWord: inWord && !isFocused,
        focused: isFocused,
        currentArrow: isDef ? currentClue.clue.arrow : null,
      };
    },
    [currentClue, currentWordRange, focused],
  );

  // Inbound remote-update path. Mirrors the local-write side-effects on the
  // DOM (set `el.value`, sync the per-cell mirror) WITHOUT firing
  // `onCellChange` and WITHOUT touching focus/direction — see the field
  // comment on `GridNavigation.applyRemoteCellUpdate` for the full rationale.
  const applyRemoteCellUpdate = useCallback((row: number, col: number, letter: string | null) => {
    const k = key({ row, col });
    const el = refs.current.get(k);
    if (!el) return;
    const value = letter ?? '';
    el.value = value;
    if (value === '') cellValuesRef.current.delete(k);
    else cellValuesRef.current.set(k, value);
    bumpEntries();
  }, [bumpEntries]);

  const localCursor = focused
    ? { position: focused, direction }
    : null;

  return {
    registerCellRef,
    highlightFor,
    handleClick,
    handleFocus,
    handleBlur,
    handleKeyDown,
    handleInput,
    currentClue,
    currentClueIndex,
    alternateClue,
    toggleDirection,
    getEntryAt,
    localCursor,
    applyRemoteCellUpdate,
  };
}
