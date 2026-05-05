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
import { BlockCellView, DefinitionCellView, LetterCellView } from './Cell';
import { CurrentCluePanel } from './CurrentCluePanel';
import { GridZoomControls } from './GridZoomControls';
import { PresenceOverlay } from './PresenceOverlay';
import { useGridNavigation, type Direction } from './useGridNavigation';

const gridContainer = css({
  display: 'grid',
  gap: '0',
  bg: 'border',
  border: '1px solid',
  borderColor: 'border',
  width: '100%',
  // Width is bounded by the TransformComponent wrapper below (maxWidth: 480px).
  // Allow border arrows on edge cells to bleed outside the grid border box.
  overflow: 'visible',
  // Omitted: touch-action:manipulation blocked pinch/pan; user-select:none blocked iOS magnifier on clue cells.
});

// Positioning frame for the optional `<PresenceOverlay>` sibling. The
// overlay is `position: absolute; inset: 0`, so it needs a positioned
// ancestor — and that ancestor must be the same box that wraps the
// `<div role="grid">` so the overlay's coordinate system shares the
// grid's pixel bounds. `overflow: visible` so pseudonym chips that
// float above the top row cells aren't clipped.
const gridFrame = css({ position: 'relative', width: '100%', overflow: 'visible' });

// Base inline style for the `TransformComponent` wrapper. Makes the
// zoom-pan viewport behave like the old raw `<div role="grid">` did:
// full available width, capped at 480px, centered under the clue panel.
// The library's stylesheet defaults this box to `width: fit-content`,
// which would collapse around `width: 100%` children — we override here
// so the outer box drives the layout and the grid inside fills it.
// `touchAction` is dynamic per `transformWrapperStyle` below — `pan-y` when
// the grid is at rest (scale 1) so a vertical swipe over the grid scrolls
// the page (the natural mobile expectation), `none` once the user has
// pinched in (scale > 1) so swipes pan the grid instead. This preserves
// native browser zoom on the page chrome (WCAG 1.4.4) and gives the right
// mobile UX: scroll-through at rest, pan-when-zoomed.
//
// `maxHeight` is layered on per render below: when the soft keyboard is
// open we shrink the wrapper to fit above the keyboard, which lets the
// player pan the grid up and reveal the rows that would otherwise sit
// underneath the keyboard with no way to reach them (the library reads
// `wrapperComponent.offsetHeight` for every pan/zoom op, so a smaller
// wrapper produces panable bounds even at scale 1).
const transformWrapperBaseStyle = {
  width: '100%',
  maxWidth: '480px',
  margin: '0 auto',
};
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

const positionKey = (p: Position) => `${p.row},${p.col}`;

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
  subscribeToRemoteCellUpdates,
  initialEntries,
  onLocalFocusChange,
  subscribeToRemotePresence,
  playersBySessionId,
  currentSessionId,
}: {
  puzzle: Puzzle;
  onCellChange?: (row: number, col: number, letter: string | null) => void;
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
}) {
  const cellByPosition = useMemo(() => {
    const m = new Map<string, Cell>();
    for (const c of puzzle.cells) m.set(positionKey(c.position), c);
    return m;
  }, [puzzle.cells]);

  const templateStyle = useMemo(
    () => ({
      gridTemplateColumns: `repeat(${puzzle.width}, 1fr)`,
      gridTemplateRows: `repeat(${puzzle.height}, auto)`,
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

  const nav = useGridNavigation(puzzle, {
    onCellChange,
    onFocusChange: onLocalFocusChange,
    getZoomScale,
  });

  // Ref for the positioned `gridFrame` div — the `PresenceOverlay`
  // measures peer-cursor cell rectangles relative to this element so
  // its layer stays pinned to the same pixel bounds as the grid even
  // under pinch-zoom or layout shifts.
  const gridFrameRef = useRef<HTMLDivElement | null>(null);

  // Blur-on-gesture coordination (iOS Safari mitigation). When an
  // `<input>` is focused, iOS Safari natively auto-scrolls / zooms to
  // Synchronous pan flag + the focused element at the moment the pan
  // started. The `focusin` listener below uses these to clamp focus
  // during a pan: if the browser tries to move focus mid-gesture (e.g.
  // mousedown on a cell focuses it before the library's preventDefault
  // takes effect), we revert to the pre-pan focus. Refs (not state)
  // because the focusin handler must read the latest value
  // synchronously, before any React render commits.
  const panningRef = useRef(false);
  const focusBeforePanRef = useRef<HTMLElement | null>(null);

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
    },
    [],
  );
  const handleZoomStart = useCallback(() => {}, []);
  const handleZoomStop = useCallback(() => {}, []);
  const handlePanningStart = useCallback(() => {
    panningRef.current = true;
    setIsPanning(true);
    const active = document.activeElement;
    focusBeforePanRef.current = active instanceof HTMLElement ? active : null;
  }, []);
  const handlePanningStop = useCallback(() => {
    setIsPanning(false);
    // The browser fires `click` after `mouseup`, which focuses the
    // input synchronously inside the same task. Library's onPanningStop
    // runs from `mouseup` — if we cleared `panningRef` immediately the
    // focusin watcher would miss the post-mouseup click→focus and the
    // cell would end up focused. Deferring the reset by one animation
    // frame keeps the watcher armed across that boundary.
    requestAnimationFrame(() => {
      panningRef.current = false;
      focusBeforePanRef.current = null;
    });
  }, []);

  // While a pan is active, lock focus to whatever it was when the pan
  // started. Two failure modes the locks counter:
  //
  //   1. Browsers focus inputs on mousedown synchronously, before the
  //      library's preventDefault on the gesture-start handler runs —
  //      so a left-click drag from a cell can transiently focus that
  //      cell. The `focusin` watcher reverts to the snapshot.
  //
  //   2. After mouseup, browsers may dispatch a `click` event on the
  //      original mousedown target. React's onClick → useGridNavigation
  //      `handleClick` → `setDirection(...)` based on the wrong cell.
  //      Even with focus reverted, direction would then point at a
  //      cell that's not focused → wrong word highlight. The `click`
  //      capture handler stops propagation so the cell handler never
  //      fires.
  //
  // Both run in capture phase to observe events regardless of
  // stopPropagation upstream.
  useEffect(() => {
    const onFocusIn = (event: FocusEvent) => {
      if (!panningRef.current) return;
      const before = focusBeforePanRef.current;
      const target = event.target;
      if (!(target instanceof HTMLElement)) return;
      if (target === before) return;
      // `preventScroll: true` keeps the user's pan visible — focusing
      // without it would scroll the cell back into view and undo the
      // gesture.
      if (before && before.isConnected) {
        before.focus({ preventScroll: true });
      } else if (typeof target.blur === 'function') {
        target.blur();
      }
    };
    const onClickCapture = (event: MouseEvent) => {
      if (!panningRef.current) return;
      event.stopPropagation();
      event.preventDefault();
    };
    document.addEventListener('focusin', onFocusIn, true);
    document.addEventListener('click', onClickCapture, true);
    return () => {
      document.removeEventListener('focusin', onFocusIn, true);
      document.removeEventListener('click', onClickCapture, true);
    };
  }, []);

  // Soft-keyboard-aware wrapper max-height. When the on-screen keyboard
  // shrinks `window.visualViewport`, the wrapper's natural full-flow
  // height stays the same — but the bottom rows now sit underneath the
  // keyboard with no way for the player to reach them (at scale 1 the
  // library's bounds collapse to zero, so panning doesn't help). We
  // measure the wrapper's distance from the visible viewport top and
  // shrink its `maxHeight` to fit the remaining space; that's enough
  // to make the library's `wrapperComponent.offsetHeight`-based bounds
  // give the player room to pan up. `null` = no override (sticky
  // default — the wrapper takes its natural flow height).
  const [wrapperMaxHeightPx, setWrapperMaxHeightPx] = useState<number | null>(null);
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
    const measure = () => {
      rafId = null;
      // The library exposes the wrapper element via the imperative ref's
      // `instance.wrapperComponent`. Reading it lazily here (vs caching
      // a separate ref) avoids racing the library's mount: the ref's
      // `instance` field is set synchronously on mount, but
      // `wrapperComponent` is populated only after the inner
      // `TransformComponent` has run its effect.
      const wrapper = transformWrapperRef.current?.instance.wrapperComponent;
      if (!wrapper) return;
      // No keyboard ⇒ visual viewport ≈ layout viewport; clear the
      // override so the wrapper takes its natural flow height. 24 px
      // tolerance absorbs URL-bar collapse and rotation transients
      // that briefly shrink visualViewport without a keyboard open.
      const keyboardOpen = vv.height < window.innerHeight - 24;
      if (!keyboardOpen) {
        setWrapperMaxHeightPx(null);
        return;
      }
      const rect = wrapper.getBoundingClientRect();
      // Available height = visual viewport bottom (in layout-px) minus
      // the wrapper's top, minus a small breathing room. `vv.offsetTop
      // + vv.height` is the visual-viewport bottom edge expressed in
      // layout-px coordinates (the same coords `rect.top` lives in).
      const visibleBottom = vv.offsetTop + vv.height;
      const available = visibleBottom - rect.top - WRAPPER_BOTTOM_MARGIN_PX;
      setWrapperMaxHeightPx(Math.max(MIN_WRAPPER_HEIGHT_PX, available));
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
      const base = { ...transformWrapperBaseStyle, touchAction, cursor } as const;
      return wrapperMaxHeightPx === null
        ? base
        : { ...base, maxHeight: `${wrapperMaxHeightPx}px` };
    },
    [wrapperMaxHeightPx, isZoomedIn, isPanning],
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
      <CurrentCluePanel clue={nav.currentClue} />
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
        - `panning.allowLeftClickPan: true` — desktop left-click drag
          pans the grid, mirroring one-finger touch pan on mobile. The
          library distinguishes "click on cell" (no movement) from "drag
          to pan" via a small movement threshold, so cell focus on click
          still works. Cursor is `grab` when zoomed and `grabbing` while
          dragging (driven by `isZoomedIn` / `panningRef`).
        - `panning.disabled` is reactively bound to `!isZoomedIn` —
          panning only makes sense when the grid is wider than the
          viewport (i.e. scale > 1). At scale 1 we keep clicks for
          cell focus and avoid any drag-as-pan ambiguity.
      */}
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
          // At scale 1 we BOTH disable panning movement AND opt out of
          // left-click capture. The library's onPanningStart handler
          // calls `preventDefault` regardless of `panning.disabled`
          // (that flag only stops the actual move), so leaving
          // allowLeftClickPan true at scale 1 swallows the cell click.
          allowLeftClickPan: isZoomedIn,
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
            `gridFrame` is the positioning ancestor for the optional
            `<PresenceOverlay>` sibling. The overlay anchors its rects
            to this box's coordinate system so cursor / word-tint /
            chip layers track the grid's pixel bounds even under
            pinch-zoom transforms applied by the wrapper above. Solo
            mode (no presence props) renders only the grid div — the
            extra `<div>` is harmless layout chrome.
          */}
          <div ref={gridFrameRef} className={gridFrame}>
            <div
              role="grid"
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
                        return (
                          <LetterCellView
                            key={key}
                            cell={cell}
                            ariaLabel={`Case ligne ${cell.position.row + 1}, colonne ${cell.position.col + 1}`}
                            inWord={highlight.currentWord}
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
            {subscribeToRemotePresence && playersBySessionId && currentSessionId ? (
              <PresenceOverlay
                containerRef={gridFrameRef}
                puzzle={puzzle}
                subscribe={subscribeToRemotePresence}
                playersBySessionId={playersBySessionId}
                currentSessionId={currentSessionId}
              />
            ) : null}
          </div>
        </TransformComponent>
      </TransformWrapper>
      <GridZoomControls
        canZoomIn={!isMaxZoom}
        canZoomOut={isZoomedIn}
        onZoomIn={() => transformWrapperRef.current?.zoomIn(0.3, 150)}
        onZoomOut={() => transformWrapperRef.current?.zoomOut(0.3, 150)}
        onReset={() => transformWrapperRef.current?.resetTransform(0)}
      />
    </>
  );
}
