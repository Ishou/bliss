import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  TransformComponent,
  TransformWrapper,
  type ReactZoomPanPinchContentRef,
} from 'react-zoom-pan-pinch';
import { css } from 'styled-system/css';
import type { GameEvent, Unsubscribe } from '@/application/game';
import type { Cell, Position, Puzzle } from '@/domain';
import type { Player, SessionId } from '@/domain/game';
import {
  BlockCellView,
  DefinitionCellView,
  LetterCellView,
  type IncomingArrow,
} from './Cell';
import { CurrentCluePanel } from './CurrentCluePanel';
import { GridMinimap } from './GridMinimap';
import { GridScrollbar } from './GridScrollbar';
import { GridZoomControls } from './GridZoomControls';
import { positionKey } from './positionKey';
import { buildCellPresenceMap, useRemotePresences } from './PresenceOverlay';
import { useGridNavigation, type Direction } from './useGridNavigation';

const gridContainer = css({
  display: 'grid',
  // gap-as-grid-line — bg shows in 1 px gap between borderless cells; no edge-doubling.
  gap: '1px',
  bg: 'gridLine',
  border: '1px solid',
  borderColor: 'gridLine',
  width: '100%',
  // Width is bounded by the TransformComponent wrapper below
  // (`transformWrapperBaseStyle.width` — a `min(100cqw, 100cqh, 720px)`
  // clamp against the flex shell's container box).
  // Allow border arrows on edge cells to bleed outside the grid border box.
  overflow: 'visible',
  // Omitted: touch-action:manipulation blocked pinch/pan; user-select:none blocked iOS magnifier on clue cells.
});

// Positioning frame for the grid. Kept as a positioned ancestor so the
// custom mouse-pan handler can attach mousedown listeners to a stable
// box (see the `useEffect` below). `overflow: visible` so cell-edge
// arrow glyphs that straddle the grid border bleed out without being
// clipped.
const gridFrame = css({ position: 'relative', width: '100%', overflow: 'visible' });

// Flex shell that wraps the `TransformComponent`. Two jobs:
//
// 1. Absorb leftover vertical space inside the route's flex column
//    (`flex: 1 1 0; min-height: 0`) so the page exactly fills `100dvh`
//    and never produces a vertical scrollbar. The chrome above (wordmark,
//    DÉMO badge, subtitle, sticky clue panel) and below (zoom controls,
//    "Créer une partie multijoueur" button) take their natural heights;
//    the grid uses what's left. Replaces PR #195's height-blind
//    `min(95vw, 80vmin, 720px)` clamp which let the grid be 720 px tall
//    on a 1080 p laptop after ~280 px of chrome had already stacked.
//
// 2. Establish a `container-type: size` so the wrapper inside can size
//    itself by `min(100cqw, 100cqh, 720px)` — i.e. the smaller of
//    available width and available height, capped at the design ceiling.
//    Container queries are the cleanest way to make a square box
//    auto-square inside a flex slot whose width and height are
//    independently constrained; an `aspect-ratio` + max-width /
//    max-height combo breaks down when a flex slot is wider than tall
//    (or vice versa) because the browser ignores `aspect-ratio` when
//    both dimensions are explicit.
//
// `align-items: center; justify-content: center` so the (possibly
// smaller) square is centered inside the shell when one axis is binding
// and the other has slack.
const gridShellStyles = css({
  flex: '1 1 0',
  minHeight: 0,
  minWidth: 0,
  width: '100%',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  containerType: 'size',
});

// Outer wrapper around gridShell + the in-flow minimap. Absorbs the
// same flex slack as gridShell did before this wrapper existed —
// `flex: 1 1 0; minHeight: 0` keeps the route's flex column from
// overflowing. gridShell keeps its own `flex: 1 1 0` so it stretches
// to fill gridArea.
//
// `position: relative` is currently unused (the minimap is in-flow,
// not absolutely positioned). Kept as defensive scaffolding in case
// an absolute child is added here later.
const gridAreaStyles = css({
  position: 'relative',
  flex: '1 1 0',
  minHeight: 0,
  width: '100%',
  display: 'flex',
  flexDirection: 'column',
});

// Base inline style for the `TransformComponent` wrapper. The wrapper
// is a `puzzle.width × puzzle.height` rectangle (aspect-ratio derived
// from the puzzle dims) sized to fit whichever container axis binds
// first. The library's stylesheet defaults this box to `width:
// fit-content`, which would collapse around `width: 100%` children; the
// explicit width here keeps the outer box driving layout and the grid
// inside fills it.
//
// Width formula: `min(100cqw, 100cqh * W/H, 720px)`. The first term
// honors the container's width; the second is the maximum width such
// that `height = width * H/W` still fits inside the container's height;
// the third caps the longer side at 720 px on very large displays.
// `aspect-ratio` then derives the height from the width — no explicit
// `height` value, so a single source of truth drives both axes.
//
// `touchAction` is dynamic per `transformWrapperStyle` below — `pan-y` when
// the grid is at rest (scale 1) so a vertical swipe over the grid scrolls
// the page (the natural mobile expectation), `none` once the user has
// pinched in (scale > 1) so swipes pan the grid instead. This preserves
// native browser zoom on the page chrome (WCAG 1.4.4) and gives the right
// mobile UX: scroll-through at rest, pan-when-zoomed.
//
// Soft-keyboard avoidance is expressed by shrinking the *shell* (see
// `shellInlineStyle` below) rather than the wrapper. The wrapper
// follows automatically because its width is expressed in
// container-query units against the shell — keeping the grid's aspect
// ratio constant as the keyboard collapses the visual viewport.
// `transformContentStyle.width: 100%` defeats the same library default on
// the inner (transformed) plane so the grid actually spans the wrapper.
// The library composes its `transform: translate3d() scale()` on top of
// any inline style we pass, so width here is preserved.
const transformContentStyle = { width: '100%' };


// Lower bound on the keyboard-aware wrapper max-height. Below this
// (a one-row peek into a 5+ row grid) the grid is too cramped to be
// usable, and any further shrink is more annoying than the alternative
// of letting the bottom of the grid sit briefly behind the keyboard.
// Picked conservatively to fit roughly two cells (~2 × 64 px) plus a
// hairline of border.
const MIN_WRAPPER_HEIGHT_PX = 140;
// Bottom-of-keyboard safety margin so the last visible row doesn't
// sit flush with the keyboard's accessory bar. iOS / Android keyboard
// edges report inconsistent visualViewport heights at the boundary;
// 8 px keeps the cell visually clear.
const WRAPPER_BOTTOM_MARGIN_PX = 8;

const rowStyles = css({ display: 'contents' });

// v1 interactive grid. Letter inputs are uncontrolled (ADR-0002 §4).
// `useGridNavigation` orchestrates focus, direction, and highlighting
// via stable handlers that read row/col from data attributes.
//
// Returns a fragment so `CurrentCluePanel` lives at the route layer's flex
// column (not nested inside an extra `<div>`). That matters for sticky:
// the panel's containing block becomes `<main>`, which is taller than the
// viewport, so `position: sticky` actually has room to stick. The previous
// nested-flex revision had the panel inside a `layout` div whose height
// equaled its content — sticky un-stuck instantly.
//
// Pinch-zoom: the grid (and only the grid) is wrapped in
// `react-zoom-pan-pinch`'s `TransformWrapper`. Native pinch is suppressed
// only on the wrapper element (`touch-action: none` in transformWrapperStyle),
// not at the page level — preserving native browser zoom on the clue panel
// and page chrome for WCAG 1.4.4 compliance (see ADR-0016). Because the page
// itself never zooms, the layout viewport never diverges from the visual
// viewport, so `position: sticky` on the clue panel works without JS.
// `onCellChange`: optional callback fired after every cell-write
// (letter typed, backspace clear, defensive paste blank). Solo callers
// omit it; multiplayer callers (Wave H) wire it to the WebSocket
// cellUpdate broadcast per ADR-0018. See `useGridNavigation` for the
// exhaustive list of write sites.
//
// `subscribeToRemoteCellUpdates`: optional subscribe-style registrar
// (Wave H · PR #19). When present, Grid attaches a handler on mount and
// detaches it on unmount; each `cellUpdated` frame is written directly
// into `el.value` of the matching uncontrolled <input> via the
// navigation hook's imperative `applyRemoteCellUpdate`. The inbound
// path explicitly DOES NOT re-fire `onCellChange` (would cause an echo
// loop) and DOES NOT move the local user's caret. Solo callers omit
// this prop and the entire mechanism — including the `useEffect` below
// — is dormant. The `Unsubscribe` return contract matches
// `GameClient.subscribe` in `@/application/game`.
//
// `initialEntries`: optional list of `{row, column, letter}` triples to
// pre-fill into the matching uncontrolled <input>s on mount. Drives the
// refresh-during-IN_PROGRESS rehydration: the route reads
// `lobby.game.entries` from the REST snapshot (or from a `lobbyState`
// WebSocket frame) and hands the list here. Applied through the same
// imperative `applyRemoteCellUpdate` path the live `cellUpdated` stream
// uses, so the same DOM-write contract holds (no `onCellChange` fire,
// no focus / direction churn). Re-applied whenever the array reference
// changes — pass a stable reference (`useMemo`-ed at the call site) to
// avoid wiping a player's local typing on every re-render.
// `onLocalFocusChange`: optional callback invoked when the local user's
// focused cell or solving direction changes. The lobby route wires this
// to `gameClient.cellFocus(...)` so peers can render the local user's
// cursor on their grids (ADR-0018 §"Presence"). Adapter-side debounce
// (200 ms in `WebSocketGameClient`) collapses bursts. Solo callers omit
// the prop and the hook never schedules an out-of-band call.
//
// `subscribeToRemotePresence`: optional subscribe-style registrar that
// receives every `GameEvent`; the overlay filters for `presenceUpdated`
// internally (mirrors `subscribeToRemoteCellUpdates`). Solo callers
// omit it and the overlay never mounts.
//
// `playersBySessionId`: optional lookup so the overlay can display
// pseudonyms on the per-cursor chips. A presence frame for a session
// not in the map is dropped silently — keeps a stale frame after a
// `playerLeft` from re-introducing a phantom cursor.
export function Grid({
  puzzle,
  onCellChange,
  onCellFilled,
  subscribeToRemoteCellUpdates,
  initialEntries,
  onLocalFocusChange,
  subscribeToRemotePresence,
  playersBySessionId,
  currentSessionId,
  validatedPositions,
  errorPositions,
  typingSessionIds,
}: {
  puzzle: Puzzle;
  onCellChange?: (row: number, col: number, letter: string | null) => void;
  // Solo auto-validation seam: invoked after a letter is typed into a
  // cell so the route's validation hook can check whether the fill
  // closed a word. Multiplayer callers omit this — the server is
  // authoritative and pushes `wordLocked` instead.
  onCellFilled?: (position: Position, direction: Direction) => void;
  subscribeToRemoteCellUpdates?: (handler: (event: GameEvent) => void) => Unsubscribe;
  initialEntries?: ReadonlyArray<{ row: number; column: number; letter: string }>;
  onLocalFocusChange?: (
    position: Position | null,
    direction: Direction | null,
  ) => void;
  subscribeToRemotePresence?: (handler: (event: GameEvent) => void) => Unsubscribe;
  playersBySessionId?: ReadonlyMap<SessionId, Player>;
  // The local player's sessionId. Required when the presence overlay is
  // mounted so the overlay skips the local player's own frames (the
  // existing `letterCellInWord` highlight + DOM caret already show
  // where they are; rendering the overlay's hue on top muddies the
  // base highlight without adding information).
  currentSessionId?: SessionId;
  // Validation result from the parent's `Vérifier` flow. Each entry is
  // a "row,col" position key; cells in the set render as locked sage.
  // The set is owned by the parent (the puzzle route) so a future
  // route — e.g. multiplayer — can wire it to a server-side check.
  validatedPositions?: ReadonlySet<string>;
  // Transient set of mis-typed cells. Renders the shake animation +
  // error ring; the parent clears the set after the animation finishes.
  errorPositions?: ReadonlySet<string>;
  // Set of remote session ids whose typing pulse should currently
  // animate. Threaded into `buildCellPresenceMap` so each peer's badge
  // gets `data-typing="true"` when their session is in the set.
  typingSessionIds?: ReadonlySet<SessionId>;
}) {
  const cellByPosition = useMemo(() => {
    const m = new Map<string, Cell>();
    for (const c of puzzle.cells) m.set(positionKey(c.position), c);
    return m;
  }, [puzzle.cells]);

  // Incoming-arrow map: for each LETTER cell that receives an entry
  // arrow from a neighbouring def cell, the arrow rendering metadata.
  // ADR-0005 §6 follow-up: arrows live on the receiving letter cell.
  // Rules:
  //   * `right` / `right-down` clue at (r, c) → arrow on the LEFT
  //     edge of (r, c+1). The receiving cell is the same regardless
  //     of straight vs bent.
  //   * `down` / `down-right` clue at (r, c) → arrow on the TOP edge
  //     of (r+1, c).
  //   * Single-clue source → arrow at the edge centre.
  //   * Two-clue source, mixed-origin (one right + one down) → both
  //     arrows at q1 (28 %), per the spec's quarter-positioning trick.
  //   * Two-clue source, same-origin → the two clues split the
  //     receiving edge into q1 (28 %) + q3 (72 %); domain order maps
  //     to the visual stack (clue 0 on top half, clue 1 on bottom).
  // Receiving cells that aren't letter cells are skipped silently —
  // a malformed puzzle (def → block, def → def) shouldn't blow up.
  const incomingArrowsByLetter = useMemo(() => {
    const m = new Map<string, IncomingArrow[]>();
    for (const cell of puzzle.cells) {
      if (cell.kind !== 'definition') continue;
      const clues = cell.clues;
      const isMulti = clues.length === 2;
      // Pre-compute origin (right-edge vs bottom-edge) for both clues
      // so the offset rule below is a single comparison.
      const origins = clues.map((q) =>
        q.arrow === 'right' || q.arrow === 'right-down' ? 'right' : 'bottom',
      );
      const sameOrigin = isMulti && origins[0] === origins[1];
      clues.forEach((q, idx) => {
        const goesRight = origins[idx] === 'right';
        const target = goesRight
          ? { row: cell.position.row, col: cell.position.col + 1 }
          : { row: cell.position.row + 1, col: cell.position.col };
        const targetCell = cellByPosition.get(positionKey(target));
        if (!targetCell || targetCell.kind !== 'letter') return;
        let offset: IncomingArrow['offset'] = 'center';
        if (isMulti) {
          if (sameOrigin) {
            offset = idx === 0 ? 'q1' : 'q3';
          } else {
            // Mixed-origin pair: spec aligns BOTH arrows to the
            // first-quarter — the right-arrow into the right neighbour
            // sits at top:28 % and the down-arrow into the bottom
            // neighbour sits at left:28 %. Visually pairs the two
            // entry arrows without crowding the cell centres.
            offset = 'q1';
          }
        }
        const entry: IncomingArrow = {
          edge: goesRight ? 'left' : 'top',
          offset,
          arrow: q.arrow,
        };
        const key = positionKey(target);
        const existing = m.get(key);
        if (existing) existing.push(entry);
        else m.set(key, [entry]);
      });
    }
    return m;
  }, [cellByPosition, puzzle.cells]);

  const templateStyle = useMemo(
    () => ({
      gridTemplateColumns: `repeat(${puzzle.width}, 1fr)`,
      // 1fr (not auto) so rows divide the wrapper's height evenly when
      // the puzzle aspect doesn't match the wrapper's. Cells carry their
      // own aspect-ratio: 1, which keeps them square inside the
      // 1fr × 1fr track they receive.
      gridTemplateRows: `repeat(${puzzle.height}, 1fr)`,
    }),
    [puzzle.height, puzzle.width],
  );

  // Ref to the `react-zoom-pan-pinch` instance. Two separate uses:
  //   1. `useGridNavigation`'s `getZoomScale` — keyboard-avoidance
  //      auto-scroll skips when the user has zoomed in (`state.scale >
  //      1.01`), so the auto-scroll doesn't fight a pinch gesture.
  //   2. The viewport-aware wrapper sizing below — the wrapper's
  //      `getBoundingClientRect()` is read on every visual-viewport
  //      resize to compute available height above the soft keyboard.
  // The ref is exposed as `{ instance, state, ...handlers }` per the
  // library's `useImperativeHandle`. `state` aliases the live mutable
  // state object, so reading `.scale` after the user pinches always
  // returns the post-gesture value (no React snapshot in the path).
  const transformWrapperRef = useRef<ReactZoomPanPinchContentRef | null>(null);

  const getZoomScale = useCallback(
    () => transformWrapperRef.current?.state.scale ?? 1,
    [],
  );

  // Synchronous pan flag, read by `useGridNavigation` to gate
  // focus/click slot-selection during a pan. Stays true for one frame
  // past the library's `onPanningStop` so the post-mouseup click +
  // focus events the browser dispatches in the same task are also
  // gated. Ref (not state) so the navigation hook reads the latest
  // value synchronously inside its handlers.
  const panningRef = useRef(false);
  const isPanningGetter = useCallback(() => panningRef.current, []);
  // The element that had DOM focus *before* a pan-initiating gesture
  // moved it. Captured by the custom mouse pan handler (below) on
  // mousedown in capture phase, or by `handlePanningStart` on touch.
  // On panning stop we revert DOM focus to this element — the revert
  // fires React's onFocus / onBlur and pulls React state back in sync.
  const focusBeforePanRef = useRef<HTMLElement | null>(null);

  const nav = useGridNavigation(puzzle, {
    onCellChange,
    onCellFilled,
    onFocusChange: onLocalFocusChange,
    getZoomScale,
    isPanning: isPanningGetter,
  });

  // Multiplayer presence map. The hook subscribes to `presenceUpdated`
  // events (returns an empty list in solo where `subscribeToRemotePresence`
  // is undefined). The builder composes the per-cell render plan from
  // remote presences + the local cursor; validated cells are subtracted
  // here so sage cells never carry a player hue.
  const remotePresences = useRemotePresences(
    subscribeToRemotePresence,
    currentSessionId,
  );
  const presenceMap = useMemo(() => {
    // Only compose the map in multiplayer mode — solo callers omit the
    // presence props and the legacy `letterCellInWord` rose tint paints
    // via the `inWord` prop instead. Keeps solo's focused-word visual
    // unchanged after this refactor.
    if (
      !subscribeToRemotePresence ||
      !playersBySessionId ||
      !currentSessionId
    ) {
      return null;
    }
    const localCursor = nav.localCursor
      ? {
          sessionId: currentSessionId,
          position: nav.localCursor.position,
          direction: nav.localCursor.direction,
        }
      : null;
    return buildCellPresenceMap({
      puzzle,
      remotePresences,
      localCursor,
      playersBySessionId,
      currentSessionId,
      validatedPositions: validatedPositions ?? new Set(),
      typingSessionIds,
    });
  }, [
    subscribeToRemotePresence,
    playersBySessionId,
    currentSessionId,
    nav.localCursor,
    puzzle,
    remotePresences,
    validatedPositions,
    typingSessionIds,
  ]);

  // Set of "row,col" keys covered by the current clue's word — pre-
  // computed so GridMinimap stays a dumb renderer.
  const currentWordKeys = useMemo(() => {
    const s = new Set<string>();
    if (!nav.currentClue) return s;
    for (const c of nav.currentClue.cells) {
      s.add(`${c.position.row},${c.position.col}`);
    }
    return s;
  }, [nav.currentClue]);

  // Letter cells the player has filled but not yet validated. Re-runs
  // on every entry write because nav.getEntryAt's callback identity
  // changes on every write (via the hook's internal version counter —
  // see useGridNavigation.ts:340-347).
  const filledPositions = useMemo(() => {
    const s = new Set<string>();
    const validated = validatedPositions ?? new Set<string>();
    for (const cell of puzzle.cells) {
      if (cell.kind !== 'letter') continue;
      const k = `${cell.position.row},${cell.position.col}`;
      if (validated.has(k)) continue;
      if (nav.getEntryAt(cell.position.row, cell.position.col) !== '') s.add(k);
    }
    return s;
  }, [puzzle.cells, validatedPositions, nav.getEntryAt]);

  // Zoom in / out centered on the currently-focused cell. When the
  // user has a slot focused, the library's `zoomToElement` keeps that
  // slot under their eyes as the scale changes — what they expect
  // when they hit `+` or `−`. Falls back to plain `zoomIn` / `zoomOut`
  // (which centre the *viewport*) if no cell is focused.
  const zoomCenteredOnFocus = useCallback((delta: number) => {
    const tw = transformWrapperRef.current;
    if (!tw) return;
    const target = tw.state.scale + delta;
    const clamped = Math.max(1, Math.min(4, target));
    if (Math.abs(clamped - tw.state.scale) < 0.001) return;
    const active = document.activeElement;
    const cell = active instanceof HTMLInputElement
      && active.closest('[role="grid"]')
      ? active
      : null;
    if (cell && clamped > 1.01) {
      tw.zoomToElement(cell, clamped, 150);
    } else if (delta > 0) {
      tw.zoomIn(delta, 150);
    } else {
      tw.zoomOut(-delta, 150);
    }
  }, []);

  // Ref for the positioned `gridFrame` div — the `PresenceOverlay`
  // measures peer-cursor cell rectangles relative to this element so
  // its layer stays pinned to the same pixel bounds as the grid even
  // under pinch-zoom or layout shifts.
  const gridFrameRef = useRef<HTMLDivElement | null>(null);

  // Natural (unscaled) size of the gridFrame in CSS pixels — fed into
  // the scrollbar / minimap math. We read offsetWidth/offsetHeight
  // (NOT getBoundingClientRect) because the parent TransformComponent
  // applies a CSS transform that getBoundingClientRect would multiply
  // through. offsetWidth/Height reflect the layout box only.
  const [gridFramePx, setGridFramePx] = useState({ width: 0, height: 0 });
  useEffect(() => {
    const el = gridFrameRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const update = () => {
      setGridFramePx({ width: el.offsetWidth, height: el.offsetHeight });
    };
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // Ref for the `gridShell` div — the keyboard-avoidance effect below
  // applies `maxHeight` here (not on the inner TransformWrapper) because
  // the wrapper is sized via `min(100cqw, 100cqh, …)` against this
  // container; shrinking the container's height shrinks the wrapper's
  // square edge, which keeps width and height in lockstep when the
  // visualViewport collapses under the soft keyboard.
  const gridShellRef = useRef<HTMLDivElement | null>(null);

  // Blur-on-gesture coordination (iOS Safari mitigation). When an
  // `<input>` is focused, iOS Safari natively auto-scrolls / zooms to
  // Reactive copies of the library's gesture state, used to drive style
  // changes that need to re-render (touch-action, cursor). ~1.01 scale
  // epsilon mirrors the threshold used elsewhere for "is the user zoomed?".
  const [isZoomedIn, setIsZoomedIn] = useState(false);
  const [isMaxZoom, setIsMaxZoom] = useState(false);
  const [isPanning, setIsPanning] = useState(false);
  // Tracks the prior frame's scale so we can detect the "crossed back
  // down to 1" transition exactly once and snap to centre. Lives outside
  // React state because rapid wheel notches would otherwise create
  // setState churn.
  const previousScaleRef = useRef(1);

  // Full transform tuple, rAF-coalesced from onTransform so the
  // scrollbars and minimap re-render in step with the library's
  // transform without a setState-per-frame.
  const [transformState, setTransformState] = useState({
    scale: 1,
    positionX: 0,
    positionY: 0,
  });
  const transformRafRef = useRef<number | null>(null);

  // Cancel any in-flight rAF on unmount so a stale callback doesn't
  // call setState on a torn-down component.
  useEffect(() => {
    return () => {
      if (transformRafRef.current !== null) {
        cancelAnimationFrame(transformRafRef.current);
        transformRafRef.current = null;
      }
    };
  }, []);

  // No focus side-effects during zoom or pan: gestures and focus state
  // are independent. To enforce that, the panning handlers snapshot the
  // current focus on start and the focusin listener (below) reverts any
  // mid-gesture focus drift.
  // `onTransform` fires on every frame the library applies a transform
  // (wheel notch, pinch step, button click animation). We use it to
  // keep `isZoomedIn` / `isMaxZoom` in step with the live scale —
  // `onZoomStop` alone debounces too long and the buttons' enabled
  // state visibly lags the cursor's `grab` cue. We also snap the grid
  // back to centre the moment scale crosses 1 on the way down: doing
  // this here (vs in `onZoomStop`) avoids the ~1s delay before the
  // library's debounced stop fires. Guard with a ref so we only call
  // `centerView` once per crossing — calling it every frame would
  // trigger a recursive transform loop. 0.01 epsilon mirrors the
  // threshold elsewhere; 4 is the `maxScale` prop on TransformWrapper
  // below — must stay in sync.
  const handleTransform = useCallback(
    (_ref: { state: { scale: number } }, state: { scale: number }) => {
      setIsZoomedIn(state.scale > 1.01);
      setIsMaxZoom(state.scale >= 4 - 0.01);
      if (state.scale <= 1.01 && previousScaleRef.current > 1.01) {
        const tw = transformWrapperRef.current;
        // 150 ms matches the button-driven zoom animation; an instant
        // snap (`0`) felt jarring relative to the rest of the gestures.
        // `previousScaleRef` already records the current scale below,
        // so we don't re-trigger this branch on subsequent frames.
        if (tw) tw.centerView(1, 150);
      }
      previousScaleRef.current = state.scale;

      // rAF-coalesce the full-tuple state update. Read the latest live
      // state from the ref inside the rAF callback so we commit the most
      // recent values the library applied (the library mutates its own
      // state object on every frame).
      if (transformRafRef.current === null) {
        transformRafRef.current = requestAnimationFrame(() => {
          transformRafRef.current = null;
          const live = transformWrapperRef.current?.state;
          if (!live) return;
          setTransformState({
            scale: live.scale,
            positionX: live.positionX,
            positionY: live.positionY,
          });
        });
      }
    },
    [],
  );
  const handleZoomStart = useCallback(() => {}, []);
  const handleZoomStop = useCallback(() => {}, []);
  // Library-driven path (touch). For touch, the library calls
  // `onPanningStart` on touchstart, before the browser would synthesise
  // any focus change (touch focus happens on touchend → click). So
  // `document.activeElement` at this point is the pre-touch focus —
  // good enough to snapshot for the revert. The custom mouse handler
  // below sets `focusBeforePanRef` on its own, so we leave it alone if
  // already populated.
  const handlePanningStart = useCallback(() => {
    panningRef.current = true;
    setIsPanning(true);
    if (focusBeforePanRef.current === null) {
      const active = document.activeElement;
      focusBeforePanRef.current =
        active instanceof HTMLElement ? active : null;
    }
  }, []);
  const handlePanningStop = useCallback(() => {
    setIsPanning(false);
    // Browser tail events (click, focusin, focusout) fire synchronously
    // after `mouseup`, in the same task as this handler. Defer the
    // teardown to the next animation frame so those events still see
    // panningRef=true and stay gated by useGridNavigation.
    requestAnimationFrame(() => {
      // Open the gate FIRST so the revert below can flow through
      // useGridNavigation (otherwise its handleFocus would gate the
      // reverted focusin and React state wouldn't catch up).
      panningRef.current = false;
      const before = focusBeforePanRef.current;
      focusBeforePanRef.current = null;
      const active = document.activeElement;
      if (active === before) return;
      if (before && before.isConnected && typeof (before as HTMLElement).focus === 'function') {
        (before as HTMLElement).focus({ preventScroll: true });
      } else if (active instanceof HTMLElement && active.closest('[role="grid"]')) {
        active.blur();
      }
    });
  }, []);

  // Custom mouse pan with a movement threshold. The library's mouse
  // pan path preventDefaults every mousedown, blocking cell focus on a
  // click without drag. We disable that (`allowLeftClickPan: false`)
  // and re-implement pan via direct `setTransform` calls when movement
  // exceeds THRESHOLD_PX. Below the threshold the click flows
  // unimpeded and the cell focuses naturally.
  //
  // Touch is unchanged — the library handles touch panning, and
  // touch click→focus already works because the browser only
  // synthesises a click on touchend with low movement.
  useEffect(() => {
    const frame = gridFrameRef.current;
    if (!frame) return;
    const THRESHOLD_PX = 4;
    let drag: {
      startX: number;
      startY: number;
      baseX: number;
      baseY: number;
      baseScale: number;
      preFocus: HTMLElement | null;
      started: boolean;
    } | null = null;

    const onMouseDown = (event: MouseEvent) => {
      if (event.button !== 0) return; // only left button
      const tw = transformWrapperRef.current;
      if (!tw || tw.state.scale <= 1.01) return; // nothing to pan
      // Capture phase fires before the browser moves focus to the
      // clicked input — `document.activeElement` here is the pre-
      // mousedown focus, the snapshot we want to revert to if a pan
      // happens.
      const active = document.activeElement;
      drag = {
        startX: event.clientX,
        startY: event.clientY,
        baseX: tw.state.positionX,
        baseY: tw.state.positionY,
        baseScale: tw.state.scale,
        preFocus: active instanceof HTMLElement ? active : null,
        started: false,
      };
    };

    const onMouseMove = (event: MouseEvent) => {
      if (!drag) return;
      const dx = event.clientX - drag.startX;
      const dy = event.clientY - drag.startY;
      if (!drag.started) {
        if (dx * dx + dy * dy < THRESHOLD_PX * THRESHOLD_PX) return;
        drag.started = true;
        // Promote to a pan: arm the gates and snapshot focus.
        panningRef.current = true;
        setIsPanning(true);
        focusBeforePanRef.current = drag.preFocus;
      }
      const tw = transformWrapperRef.current;
      if (tw) {
        tw.setTransform(
          drag.baseX + dx,
          drag.baseY + dy,
          drag.baseScale,
          0,
        );
      }
    };

    const onMouseUp = () => {
      const local = drag;
      drag = null;
      if (!local || !local.started) return;
      // End the pan: same revert flow as `handlePanningStop`.
      setIsPanning(false);
      requestAnimationFrame(() => {
        panningRef.current = false;
        const before = focusBeforePanRef.current;
        focusBeforePanRef.current = null;
        const active = document.activeElement;
        if (active === before) return;
        if (
          before instanceof HTMLElement &&
          before !== document.body &&
          before.isConnected
        ) {
          before.focus({ preventScroll: true });
        } else if (
          active instanceof HTMLElement &&
          active.closest('[role="grid"]')
        ) {
          active.blur();
        }
      });
    };

    frame.addEventListener('mousedown', onMouseDown, true);
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
    return () => {
      frame.removeEventListener('mousedown', onMouseDown, true);
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
    };
  }, []);

  // Soft-keyboard-aware shell max-height. When the on-screen keyboard
  // shrinks `window.visualViewport`, the shell's natural flex-grow
  // height (a slice of `100dvh`) stays the same on browsers that don't
  // refresh `dvh` for the keyboard — but the bottom rows now sit
  // underneath the keyboard with no way for the player to reach them
  // (at scale 1 the library's bounds collapse to zero, so panning
  // doesn't help). We measure the shell's distance from the visible
  // viewport top and shrink its `maxHeight` to fit the remaining
  // space; because the inner wrapper is sized via
  // `min(100cqw, 100cqh, 720px)` against the shell, that shrink
  // propagates to both the wrapper's width and height in lockstep —
  // keeping the grid square. `null` = no override (the shell takes its
  // natural flex-grow height).
  const [shellMaxHeightPx, setShellMaxHeightPx] = useState<number | null>(null);
  // Ref-mirror of the latest committed `shellMaxHeightPx`, read by the
  // sub-pixel guard below without re-subscribing the effect on every
  // value change. Kept in sync with the state setter (one place writes
  // both).
  const shellMaxHeightRef = useRef<number | null>(null);
  useEffect(() => {
    if (typeof window === 'undefined' || !window.visualViewport) return;
    const vv = window.visualViewport;
    // rAF coalescing: iOS Safari fires `resize`/`scroll` at ~60Hz on
    // `window.visualViewport` during a pinch; per-event measurement +
    // setState would do `getBoundingClientRect` work and trigger a
    // React render every frame. We collapse bursts to one update per
    // animation frame — the pending frame is set by the first event
    // and cleared inside the actual measurement; subsequent events
    // in the same frame are no-ops. The cleanup cancels any in-flight
    // frame so a stale callback doesn't run after unmount and call
    // `setState` on a torn-down component.
    let rafId: number | null = null;
    // Sub-pixel jitter tolerance for the shell's `maxHeight`. iOS
    // Safari with the soft keyboard open fires `visualViewport.scroll`
    // every frame as the address bar / accessory bar settles; each
    // frame's `vv.offsetTop + vv.height - rect.top` differs by ≤ 1 px
    // from the last (sub-pixel viewport math). PR #198 broke FitText's
    // INNER measure-then-resize loop with a `lastCw`/`lastCh` guard,
    // but the OUTER cascade survived: every frame of jittery
    // visualViewport ⇒ new `setShellMaxHeightPx` ⇒ shell's inline
    // `maxHeight` mutates by 1 px ⇒ container-query units re-resolve
    // ⇒ each cell's ResizeObserver fires with new `cw`/`ch` ⇒ FitText
    // re-runs the binary search ⇒ on cells whose clue text straddles
    // the integer-px font choice the new "best" alternates between
    // two adjacent values ⇒ visible flicker. The fix mirrors PR
    // #198's pattern up the tree: bail out of `setShellMaxHeightPx`
    // when the new value is within ε of the last applied one. ε = 1 px
    // is enough to absorb the observed jitter while still reacting to
    // a real keyboard open / close (which moves the available height
    // by ~250 px on phones — three orders of magnitude above ε).
    const SHELL_MAX_HEIGHT_EPSILON_PX = 1;
    const commit = (next: number | null) => {
      const prev = shellMaxHeightRef.current;
      if (prev === next) return; // null === null or identical scalar
      if (prev !== null && next !== null && Math.abs(next - prev) <= SHELL_MAX_HEIGHT_EPSILON_PX) {
        return;
      }
      shellMaxHeightRef.current = next;
      setShellMaxHeightPx(next);
    };
    const measure = () => {
      rafId = null;
      const shell = gridShellRef.current;
      if (!shell) return;
      // No keyboard ⇒ visual viewport ≈ layout viewport; clear the
      // override so the shell takes its natural flex-grow height. 24 px
      // tolerance absorbs URL-bar collapse and rotation transients
      // that briefly shrink visualViewport without a keyboard open.
      const keyboardOpen = vv.height < window.innerHeight - 24;
      if (!keyboardOpen) {
        commit(null);
        return;
      }
      const rect = shell.getBoundingClientRect();
      // Available height = visual viewport bottom (in layout-px) minus
      // the shell's top, minus a small breathing room. `vv.offsetTop
      // + vv.height` is the visual-viewport bottom edge expressed in
      // layout-px coordinates (the same coords `rect.top` lives in).
      const visibleBottom = vv.offsetTop + vv.height;
      const available = visibleBottom - rect.top - WRAPPER_BOTTOM_MARGIN_PX;
      commit(Math.max(MIN_WRAPPER_HEIGHT_PX, available));
    };
    const onViewportChange = () => {
      if (rafId !== null) return; // measurement already scheduled this frame
      rafId = requestAnimationFrame(measure);
    };
    // Initial sync measurement so the wrapper has the right max-height
    // from the first render — no need to wait a frame on mount.
    measure();
    // `resize` fires when the keyboard opens / closes; `scroll` fires
    // as the user pans the visual viewport (e.g. native zoom on the
    // page chrome). Both can shift the wrapper's `rect.top`, so both
    // re-trigger the measurement. Listeners are passive by spec.
    vv.addEventListener('resize', onViewportChange);
    vv.addEventListener('scroll', onViewportChange);
    return () => {
      vv.removeEventListener('resize', onViewportChange);
      vv.removeEventListener('scroll', onViewportChange);
      if (rafId !== null) cancelAnimationFrame(rafId);
    };
  }, []);
  const transformWrapperStyle = useMemo(
    () => {
      const touchAction = isZoomedIn ? 'none' : 'pan-y';
      // Cursor telegraphs "draggable to pan" on desktop. Only meaningful
      // once zoomed in (at scale 1 there's nothing to pan to). While the
      // user is mid-drag, switch to `grabbing` for the closed-fist feel.
      const cursor = isPanning ? 'grabbing' : (isZoomedIn ? 'grab' : 'auto');
      return {
        // Keep width/aspect-ratio here as a defensive pass-through:
        // the stage now provides the bounding box, but having these
        // on the TransformWrapper too keeps the library's internal
        // geometry consistent and avoids any pinch/pan regressions.
        // `margin: 0 auto` is intentionally omitted — the stage handles
        // centering inside gridShell now.
        width: `min(100cqw, calc(100cqh * ${puzzle.width} / ${puzzle.height}), 720px)`,
        aspectRatio: `${puzzle.width} / ${puzzle.height}`,
        touchAction,
        cursor,
      } as const;
    },
    [isZoomedIn, isPanning, puzzle.width, puzzle.height],
  );

  // "stage" box: wraps TransformWrapper AND the overlay siblings so the
  // overlay's `position: absolute; inset: 0` resolves relative to the
  // grid's bounding box rather than the full gridShell. Sized identically
  // to transformWrapperStyle's width / aspect-ratio so both children share
  // the same containing block. `position: relative` makes it the
  // positioned ancestor for the absolutely-positioned overlayFrame.
  // `margin: 0 auto` re-centers the stage inside the flex gridShell
  // (mirrors the `margin: 0 auto` that was on transformWrapperStyle).
  const stageStyle = useMemo(
    () => ({
      width: `min(100cqw, calc(100cqh * ${puzzle.width} / ${puzzle.height}), 720px)`,
      aspectRatio: `${puzzle.width} / ${puzzle.height}`,
      margin: '0 auto',
      position: 'relative' as const,
    }),
    [puzzle.width, puzzle.height],
  );

  // Inline style for the overlay div that sits on top of the
  // TransformWrapper (as a sibling inside `stage`, not gridShell).
  // `position: absolute; inset: 0` covers the full stage area which is
  // sized exactly like the grid — so scrollbars track the grid edges and
  // the minimap sits in the grid's corner, not the shell's corner.
  // `pointer-events: none` on this container prevents the transparent
  // area from swallowing grid clicks; the scrollbar tracks and minimap
  // re-enable pointer events on themselves via `pointer-events: auto`
  // in their CSS classes.
  // Note: this overlay does NOT wrap the TransformWrapper — the
  // TransformWrapper is a separate child of `stage` so its pointer
  // events remain fully intact.
  const overlayFrameStyle = useMemo(
    () => ({
      position: 'absolute' as const,
      inset: 0,
      pointerEvents: 'none' as const,
    }),
    [],
  );
  // Shell maxHeight override — only set when the soft keyboard is open
  // (see the keyboard-avoidance effect above). Inline so React mutates
  // the rendered DOM in step with the visualViewport state without a
  // class-name churn.
  const shellInlineStyle = useMemo(
    () => (shellMaxHeightPx === null ? undefined : { maxHeight: `${shellMaxHeightPx}px` }),
    [shellMaxHeightPx],
  );

  // Wire the inbound multiplayer path. Stable across renders because the
  // hook returns a stable `applyRemoteCellUpdate` callback and we depend
  // on the subscribe registrar reference (callers should keep it stable
  // — pass `gameClient.subscribe` directly; the filter below ignores
  // non-`cellUpdated` frames so no adapter wrapper is needed at the call site).
  // CellUpdatedEvent.column maps to the grid's `col` axis.
  const applyRemoteCellUpdate = nav.applyRemoteCellUpdate;
  useEffect(() => {
    if (!subscribeToRemoteCellUpdates) return;
    const unsubscribe = subscribeToRemoteCellUpdates((event) => {
      if (event.type !== 'cellUpdated') return;
      applyRemoteCellUpdate(event.row, event.column, event.letter);
    });
    return unsubscribe;
  }, [subscribeToRemoteCellUpdates, applyRemoteCellUpdate]);

  // Initial-entries rehydration. Replays each entry through the same
  // `applyRemoteCellUpdate` path a live `cellUpdated` frame would take,
  // so the contract is identical: writes go straight to `el.value`
  // (uncontrolled per ADR-0002 §4), no `onCellChange` fires (would echo
  // back to the server), no focus or direction change. This effect runs
  // after `registerCellRef` callbacks have populated the refs map (refs
  // attach during render-commit; the effect runs after commit, so the
  // map is ready). Re-runs only when the entries array reference changes
  // — a stable reference at the call site keeps a player's mid-typing
  // letters from being overwritten on every parent re-render.
  useEffect(() => {
    if (!initialEntries || initialEntries.length === 0) return;
    for (const entry of initialEntries) {
      applyRemoteCellUpdate(entry.row, entry.column, entry.letter);
    }
  }, [initialEntries, applyRemoteCellUpdate]);

  const rows: { row: number; cells: (Cell | null)[] }[] = [];
  for (let row = 0; row < puzzle.height; row++) {
    const cellsInRow: (Cell | null)[] = [];
    for (let col = 0; col < puzzle.width; col++) {
      cellsInRow.push(cellByPosition.get(positionKey({ row, col })) ?? null);
    }
    rows.push({ row, cells: cellsInRow });
  }

  return (
    <>
      <CurrentCluePanel
        clue={nav.currentClue}
        cellIndex={nav.currentClueIndex}
        alternateClue={nav.alternateClue}
        onSwitchDirection={nav.toggleDirection}
        getEntryAt={nav.getEntryAt}
      />
      {/*
        TransformWrapper config rationale:
        - `minScale={1}` — never zoom out below 100%, so the grid always
          fills its 480px-cap container at rest. Zooming out further would
          leave empty padding around the grid for no benefit.
        - `maxScale={4}` — caps zoom at 4× so a single cell on a 5-col
          puzzle (~96px at base) renders ~384px. Plenty for thumb-distance
          reading without making the grid uselessly large.
        - `centerOnInit` — first paint puts the grid centered in the
          wrapper. Without this the library can leave a small offset from
          its bounds-padding logic on certain initial sizes.
        - `wheel.step: 0.05` — desktop mouse wheel zooms the grid. The
          default 0.2 is jumpy; 0.05 (5% per notch) is smooth and close
          to native trackpad pinch. To gate behind a modifier
          (ctrl+wheel = zoom, plain wheel = page scroll) set
          `activationKeys: ['Control', 'Meta']`.
        - `doubleClick.disabled` — a double-tap on a cell would otherwise
          zoom-in on that cell, fighting the focus + cursor behavior we
          rely on for letter input. Disabled keeps taps purely about focus.
        - `panning.velocityDisabled` — momentum/inertia after a flick
          feels disorienting in a fixed-content puzzle (you expect the
          grid to land where your finger lifts).
        - `panning.allowLeftClickPan: false` — the library's mouse-pan
          path calls `event.preventDefault()` on EVERY mousedown that
          would start a pan, blocking the browser's default cell-focus
          behavior even on a click-without-drag. We re-implement mouse
          pan ourselves below (see `useEffect` with `mousedown` /
          `mousemove` / `mouseup` listeners) with a movement threshold
          so a real click still falls through to focus the cell.
        - `panning.disabled` is reactively bound to `!isZoomedIn` —
          gates touch panning (still library-driven) at scale 1.
      */}
      <div className={gridAreaStyles}>
      <div
        ref={gridShellRef}
        className={gridShellStyles}
        style={shellInlineStyle}
        data-testid="grid-shell"
      >
      {/*
        stage: same width/aspect-ratio as the grid, `position: relative`,
        so the absolutely-positioned overlayFrame (and its scrollbar /
        minimap children) resolve their `inset: 0` against the grid's
        bounding box rather than the full gridShell. The stage replaces
        the `margin: 0 auto` on transformWrapperStyle as the centering
        box inside the flex shell.
      */}
      <div style={stageStyle} data-testid="grid-stage">
      <TransformWrapper
        ref={transformWrapperRef}
        minScale={1}
        maxScale={4}
        initialScale={1}
        centerOnInit
        wheel={{ step: 0.05 }}
        doubleClick={{ disabled: true }}
        panning={{
          velocityDisabled: true,
          // Left-click mouse pan is implemented by the custom mouse
          // handlers below (movement-threshold), NOT the library —
          // see the rationale block above.
          allowLeftClickPan: false,
          // Touch panning still flows through the library; at scale 1
          // there's nothing to pan, so disable to keep one-finger
          // vertical scroll passing through to the page.
          disabled: !isZoomedIn,
        }}
        // Blur-on-gesture: iOS Safari fights pinch / pan with a native
        // focus-snap (auto-scrolls / zooms to keep the focused <input>
        // visible during any layout change, including the library's
        // JS-driven CSS transform). Blurring on gesture start stops
        // the fight; restoring focus on gesture end re-opens the
        // keyboard so the user can resume typing. Refs only — no
        // re-renders during the gesture. See the gesture-handler
        // block above for the to-restore + tap-during-gesture logic.
        onZoomStart={handleZoomStart}
        onZoomStop={handleZoomStop}
        onTransform={handleTransform}
        onPanningStart={handlePanningStart}
        onPanningStop={handlePanningStop}
      >
        <TransformComponent
          wrapperStyle={transformWrapperStyle}
          contentStyle={transformContentStyle}
        >
          {/*
            `gridFrame` wraps `<div role="grid">` to provide a positioned
            box for the custom mouse-pan handlers. Multiplayer presence
            visuals now live inside each `LetterCellView` (no overlay
            sibling) — the wrapper stays for the pan-listener stability.
          */}
          <div ref={gridFrameRef} className={gridFrame}>
            <div
              role="grid"
              id="puzzle-grid"
              aria-label={puzzle.title}
              lang={puzzle.language}
              className={gridContainer}
              style={templateStyle}
            >
              {rows.map(({ row, cells }) => (
                <div key={row} role="row" className={rowStyles}>
                  {cells.map((cell, col) => {
                    if (cell === null) {
                      return (
                        <BlockCellView
                          key={`empty-${row}-${col}`}
                          cell={{ kind: 'block', position: { row, col } }}
                        />
                      );
                    }
                    const key = positionKey(cell.position);
                    switch (cell.kind) {
                      case 'letter': {
                        const highlight = nav.highlightFor(cell.position);
                        const validated = validatedPositions?.has(key) ?? false;
                        const error = errorPositions?.has(key) ?? false;
                        const incomingArrows = incomingArrowsByLetter.get(key);
                        const presence = presenceMap?.get(key);
                        const inWord = highlight.currentWord;
                        const wordIndex =
                          inWord && nav.currentClue
                            ? nav.currentClue.cells.findIndex(
                                (c) =>
                                  c.position.row === cell.position.row &&
                                  c.position.col === cell.position.col,
                              )
                            : -1;
                        const letter = nav.getEntryAt(
                          cell.position.row,
                          cell.position.col,
                        );
                        const ordinal = (n: number) => (n === 1 ? '1ère' : `${n}ème`);
                        const ariaLabel =
                          wordIndex >= 0
                            ? `${ordinal(wordIndex + 1)} lettre : ${letter !== '' ? letter : 'vide'}`
                            : `Case ligne ${cell.position.row + 1}, colonne ${cell.position.col + 1}`;
                        return (
                          <LetterCellView
                            key={key}
                            cell={cell}
                            ariaLabel={ariaLabel}
                            inWord={highlight.currentWord}
                            focused={highlight.focused}
                            validated={validated}
                            error={error}
                            presence={presence}
                            incomingArrows={incomingArrows}
                            inputRef={nav.registerCellRef}
                            onClick={nav.handleClick}
                            onFocus={nav.handleFocus}
                            onBlur={nav.handleBlur}
                            onKeyDown={nav.handleKeyDown}
                            onInput={nav.handleInput}
                          />
                        );
                      }
                      case 'definition': {
                        const highlight = nav.highlightFor(cell.position);
                        return (
                          <DefinitionCellView
                            key={key}
                            cell={cell}
                            currentArrow={highlight.currentArrow}
                          />
                        );
                      }
                      case 'block':
                        return <BlockCellView key={key} cell={cell} />;
                    }
                  })}
                </div>
              ))}
            </div>
          </div>
        </TransformComponent>
      </TransformWrapper>
      {/*
        overlayFrame: `position: absolute; inset: 0` div that covers the
        full stage area (the grid's bounding box). Scrollbar tracks and minimap
        are `position: absolute` children positioned at the grid's edges. This
        frame sits OUTSIDE the TransformComponent so the overlays bypass the
        library's `overflow: hidden` wrapper and are NOT subject to the CSS
        transform scaling. `pointer-events: none` on the frame ensures the
        transparent area does not intercept grid clicks; the scrollbar tracks
        and minimap re-enable pointer events on themselves via
        `pointer-events: auto` in their CSS classes.
      */}
      {isZoomedIn && gridFramePx.width > 0 && gridFramePx.height > 0 && (
        <div style={overlayFrameStyle}>
          <GridScrollbar
            orientation="vertical"
            transformRef={transformWrapperRef}
            scale={transformState.scale}
            positionX={transformState.positionX}
            positionY={transformState.positionY}
            contentWidth={gridFramePx.width}
            contentHeight={gridFramePx.height}
          />
          <GridScrollbar
            orientation="horizontal"
            transformRef={transformWrapperRef}
            scale={transformState.scale}
            positionX={transformState.positionX}
            positionY={transformState.positionY}
            contentWidth={gridFramePx.width}
            contentHeight={gridFramePx.height}
          />
        </div>
      )}
      </div>{/* stage */}
      </div>{/* gridShell */}
      {isZoomedIn && gridFramePx.width > 0 && gridFramePx.height > 0 && (
        <GridMinimap
          puzzle={puzzle}
          validatedPositions={validatedPositions ?? new Set()}
          filledPositions={filledPositions}
          currentWordKeys={currentWordKeys}
          localCursor={nav.localCursor}
          transformRef={transformWrapperRef}
          scale={transformState.scale}
          positionX={transformState.positionX}
          positionY={transformState.positionY}
          contentWidth={gridFramePx.width}
          contentHeight={gridFramePx.height}
        />
      )}
      </div>{/* gridArea */}
      <GridZoomControls
        canZoomIn={!isMaxZoom}
        canZoomOut={isZoomedIn}
        onZoomIn={() => zoomCenteredOnFocus(0.3)}
        onZoomOut={() => zoomCenteredOnFocus(-0.3)}
        onReset={() => transformWrapperRef.current?.resetTransform(0)}
      />
    </>
  );
}
