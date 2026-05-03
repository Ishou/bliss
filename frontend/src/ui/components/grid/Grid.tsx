import { useEffect, useMemo, useRef } from 'react';
import { TransformComponent, TransformWrapper } from 'react-zoom-pan-pinch';
import { css } from 'styled-system/css';
import type { GameEvent, Unsubscribe } from '@/application/game';
import type { Cell, Position, Puzzle } from '@/domain';
import type { Player, SessionId } from '@/domain/game';
import { BlockCellView, DefinitionCellView, LetterCellView } from './Cell';
import { CurrentCluePanel } from './CurrentCluePanel';
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

// `transformWrapperStyle` makes the zoom-pan viewport behave like the old
// raw `<div role="grid">` did: full available width, capped at 480px,
// centered under the clue panel. The library's stylesheet defaults this
// box to `width: fit-content`, which would collapse around `width: 100%`
// children — we override here so the outer box drives the layout and the
// grid inside fills it.
// `touchAction: 'none'` scopes native-pinch suppression to this element only,
// keeping native browser zoom available on the clue panel and page chrome
// (required for WCAG 1.4.4 on mobile — pinch IS browser zoom on touch devices).
const transformWrapperStyle = { width: '100%', maxWidth: '480px', margin: '0 auto', touchAction: 'none' as const };
// `transformContentStyle.width: 100%` defeats the same library default on
// the inner (transformed) plane so the grid actually spans the wrapper.
// The library composes its `transform: translate3d() scale()` on top of
// any inline style we pass, so width here is preserved.
const transformContentStyle = { width: '100%' };

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

  const nav = useGridNavigation(puzzle, {
    onCellChange,
    onFocusChange: onLocalFocusChange,
  });

  // Ref for the positioned `gridFrame` div — the `PresenceOverlay`
  // measures peer-cursor cell rectangles relative to this element so
  // its layer stays pinned to the same pixel bounds as the grid even
  // under pinch-zoom or layout shifts.
  const gridFrameRef = useRef<HTMLDivElement | null>(null);

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
        - `wheel.disabled` — desktop mouse wheel scrolls the page (the
          natural expectation), not zooms the grid. Pinch on a trackpad
          still zooms, which is the desktop equivalent of mobile pinch.
        - `doubleClick.disabled` — a double-tap on a cell would otherwise
          zoom-in on that cell, fighting the focus + cursor behavior we
          rely on for letter input. Disabled keeps taps purely about focus.
        - `panning.velocityDisabled` — momentum/inertia after a flick
          feels disorienting in a fixed-content puzzle (you expect the
          grid to land where your finger lifts).
        - `panning.allowLeftClickPan: false` — prevents desktop left-click
          drag from initiating a pan (would conflict with future
          drag-to-select features and is unnecessary at scale 1, the
          default desktop state). One-finger touch panning still works
          on mobile because that flows through the touch handlers, not
          the mouse handlers.
      */}
      <TransformWrapper
        minScale={1}
        maxScale={4}
        initialScale={1}
        centerOnInit
        wheel={{ disabled: true }}
        doubleClick={{ disabled: true }}
        panning={{ velocityDisabled: true, allowLeftClickPan: false }}
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
            {subscribeToRemotePresence && playersBySessionId ? (
              <PresenceOverlay
                containerRef={gridFrameRef}
                puzzle={puzzle}
                subscribe={subscribeToRemotePresence}
                playersBySessionId={playersBySessionId}
              />
            ) : null}
          </div>
        </TransformComponent>
      </TransformWrapper>
    </>
  );
}
