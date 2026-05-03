import { useEffect, useState } from 'react';
import { css } from 'styled-system/css';
import type {
  GameEvent,
  PresenceUpdatedEvent,
  Unsubscribe,
} from '@/application/game';
import type { Player, SessionId } from '@/domain/game';
import type { Position, Puzzle } from '@/domain';
import { playerColorVars } from '@/ui/lib/playerColor';
import { wordRange } from './wordRange';

// Decorative overlay layered above the grid that renders peer cursors.
// ADR-0002 §2 (Ark UI policy): Ark UI ships no primitive for "absolute
// rectangle positioned over a sibling rect", so this is a leaf component
// using raw HTML — explicitly allowed by §2 for low-level visual
// adornments. Overall accessibility posture: the overlay is `aria-hidden`
// so screen readers ignore it; keyboard nav stays through the cells
// themselves (peer cursors are visual sugar only).
//
// Subscription model: the overlay attaches its own subscription to the
// game event stream and discriminates on `presenceUpdated` — same
// pattern Grid uses for `cellUpdated`. Local state is an array keyed by
// `sessionId` so a fresh frame for the same session overwrites the
// previous one. `null` row/column drops the entry (peer focused
// nothing). Insertion order is preserved (new presences appended), so
// the rendering loop walks "oldest first → newest last", and the
// pseudonym chip stack reverses for "most recent first" as the plan
// asks for.
//
// Positioning: each remote presence's focused cell is queried by its
// `data-row` / `data-col` (PR #141 pattern) on the wrapping
// `[role="gridcell"]` div. We measure with `getBoundingClientRect()`
// against the overlay's container so layer offsets are independent of
// page scroll and zoom-pan transforms. A `ResizeObserver` on the
// container re-measures on layout change (window resize, font swap,
// dynamic-viewport keyboard pop). Subsequent presence-event arrivals
// also re-measure their own cell at render time, so a layout shift
// between frames does not desync the overlay.

// Layer / element styles. `mix-blend-mode: multiply` on the word-tint
// layer means two overlapping translucent hues darken naturally
// together without per-pixel maths — the browser does it.
const overlayContainer = css({
  position: 'absolute',
  inset: 0,
  pointerEvents: 'none',
  // The grid's `data-cell-kind="letter"` cells render at z-index ~0,
  // and the definition cell's arrows at z-index 2. Overlay rings sit
  // above those (z=3) but pseudonym chips on top (z=4) so a chip from
  // one player never sits behind a ring from another.
  zIndex: 3,
});

const wordTintRect = css({
  position: 'absolute',
  // `--player-color-soft` is set per-rect via inline `style`. Multiply
  // blend mode darkens overlapping tints together — two players on the
  // same cell of an across vs down word produce a richer combined hue.
  backgroundColor: 'var(--player-color-soft)',
  mixBlendMode: 'multiply',
  borderRadius: '2px',
  pointerEvents: 'none',
});

const ringEl = css({
  position: 'absolute',
  // 2 px stroke; concentric rings stack via inline `inset` per index so
  // overlapping presences are visible without overpainting.
  border: '2px solid var(--player-color)',
  borderRadius: '4px',
  pointerEvents: 'none',
  boxSizing: 'border-box',
});

const chipEl = css({
  position: 'absolute',
  // Float above the focused cell (translateY(-100%) to anchor the chip
  // BOTTOM at the cell TOP, with a few px breathing room).
  display: 'inline-flex',
  alignItems: 'center',
  gap: '4px',
  paddingInline: '6px',
  paddingBlock: '2px',
  fontSize: 'xxs',
  fontWeight: 'medium',
  color: 'var(--player-text-color)',
  bg: 'breath',
  border: '1px solid var(--player-color)',
  borderRadius: '9999px',
  whiteSpace: 'nowrap',
  pointerEvents: 'none',
  zIndex: 4,
  // Slight shadow to lift the chip off the grid surface.
  boxShadow: '0 1px 3px rgba(27, 40, 69, 0.15)',
});

const chipDot = css({
  display: 'inline-block',
  width: '6px',
  height: '6px',
  borderRadius: '9999px',
  backgroundColor: 'var(--player-color)',
});

interface PresenceState {
  readonly sessionId: SessionId;
  readonly position: Position;
  readonly direction: 'across' | 'down';
}

// Ring stroke + gap geometry. Concentric rings stack inward 5 px per
// index (2 px stroke + 3 px gap) so up to ~4 simultaneous presences on
// the same cell stay legible at the typical 48–72 px cell width.
const RING_STEP_PX = 5;

export function PresenceOverlay({
  containerRef,
  puzzle,
  subscribe,
  playersBySessionId,
  currentSessionId,
}: {
  // Ref to the grid container the overlay measures cells against.
  // Bounding rectangles are computed relative to this element so layer
  // offsets are independent of page scroll / pinch-zoom transforms.
  readonly containerRef: React.RefObject<HTMLElement | null>;
  readonly puzzle: Puzzle;
  // Subscribe-style registrar. Receives ALL `GameEvent`s; we filter for
  // `presenceUpdated` internally (matches the existing
  // `subscribeToRemoteCellUpdates` pattern in Grid).
  readonly subscribe: (handler: (event: GameEvent) => void) => Unsubscribe;
  // Lookup: sessionId → Player (used for the pseudonym chip text).
  // Missing sessions are skipped (a presence frame for a player who
  // hasn't joined yet is dropped silently).
  readonly playersBySessionId: ReadonlyMap<SessionId, Player>;
  // The local player's sessionId. Their own presenceUpdated frames are
  // filtered out — the local "current word" highlight (leaf.50 background
  // on `letterCellInWord` cells) plus the DOM caret already show where
  // they are, and stacking the overlay's hue on top of leaf.50 muddies
  // the visual without adding information.
  readonly currentSessionId: SessionId;
}) {
  // Insertion-ordered list. A fresh presence overwrites the previous
  // entry but keeps its position in iteration order, so chip-stacking
  // ordering stays stable. Stored as an array (rather than a Map) so
  // React tracks identity correctly for re-renders.
  const [presences, setPresences] = useState<readonly PresenceState[]>([]);
  // Layout tick: bumped on `ResizeObserver` callback (or any resize)
  // to force a re-render so the rect-measurement effect re-runs.
  const [, setLayoutTick] = useState(0);

  // Subscribe on mount, detach on unmount. Same registrar pattern as
  // `subscribeToRemoteCellUpdates` in Grid. The local player's own
  // frames are dropped here — see the prop docs for why.
  useEffect(() => {
    const unsubscribe = subscribe((event) => {
      if (event.type !== 'presenceUpdated') return;
      if (event.sessionId === currentSessionId) return;
      applyPresenceUpdate(event, setPresences);
    });
    return unsubscribe;
  }, [subscribe, currentSessionId]);

  // Re-measure on container resize. jsdom polyfills `ResizeObserver` to
  // a no-op (vitest.setup.ts), so this is a noop in unit tests; the
  // tests still cover initial measurement because the first render
  // measures synchronously after mount.
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    // Bump the layout tick on mount so the very first render — which
    // saw `containerRef.current === null` because refs commit AFTER
    // render — re-runs with the populated ref. Without this, an
    // overlay that mounts BEFORE its first presenceUpdated arrives
    // would render `null` for every layer (no presences), and the
    // subsequent presence dispatch would re-render in the same React
    // commit batch, so the layers would still see no rect because
    // jsdom returns 0×0 from `getBoundingClientRect()` until the
    // browser has laid out — which it never does in jsdom anyway.
    // The bump ensures the render that follows the dispatch reads
    // the ref synchronously.
    setLayoutTick((tick) => tick + 1);
    const observer = new ResizeObserver(() => {
      setLayoutTick((tick) => tick + 1);
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, [containerRef]);

  // Build the render plan. Each presence contributes one rect per
  // word-range cell and one ring + one chip on the focused cell. Cell
  // rectangles are measured relative to the overlay container so the
  // layer never leaks out into the page (the grid wrapper already
  // clips us via `overflow: visible` — we just stay aligned).
  const container = containerRef.current;
  const containerRect = container?.getBoundingClientRect();

  // Index presences by focused cell so we can render concentric rings
  // when multiple peers focus the same cell. `Map` insertion order
  // mirrors `presences` order, which matches the wire arrival order.
  const ringsByCell = new Map<string, PresenceState[]>();
  for (const p of presences) {
    if (!playersBySessionId.has(p.sessionId)) continue;
    const cellKey = `${p.position.row},${p.position.col}`;
    const list = ringsByCell.get(cellKey) ?? [];
    list.push(p);
    ringsByCell.set(cellKey, list);
  }

  return (
    <div className={overlayContainer} aria-hidden="true" data-testid="presence-overlay">
      {/* Layer 1: word-tint rectangles, one per (presence, cell-in-word). */}
      {container && containerRect ? presences.flatMap((p) => {
        if (!playersBySessionId.has(p.sessionId)) return [];
        const range = wordRange(puzzle, p.position, p.direction);
        return range.map((pos) => {
          const rect = cellRect(container, pos);
          if (!rect) return null;
          const top = rect.top - containerRect.top;
          const left = rect.left - containerRect.left;
          return (
            <div
              key={`tint-${p.sessionId}-${pos.row}-${pos.col}`}
              className={wordTintRect}
              style={{
                ...playerColorVars(p.sessionId),
                top,
                left,
                width: rect.width,
                height: rect.height,
              }}
              data-testid="presence-word-tint"
              data-session-id={p.sessionId}
              data-row={pos.row}
              data-col={pos.col}
            />
          );
        });
      }) : null}

      {/* Layer 2: focused-cell rings, concentric per index when multiple
          presences land on the same cell. */}
      {container && containerRect ? Array.from(ringsByCell.entries()).flatMap(
        ([cellKey, list]) => list.map((p, index) => {
          const rect = cellRect(container, p.position);
          if (!rect) return null;
          const top = rect.top - containerRect.top - index * RING_STEP_PX;
          const left = rect.left - containerRect.left - index * RING_STEP_PX;
          const width = rect.width + index * RING_STEP_PX * 2;
          const height = rect.height + index * RING_STEP_PX * 2;
          return (
            <div
              key={`ring-${cellKey}-${p.sessionId}`}
              className={ringEl}
              style={{
                ...playerColorVars(p.sessionId),
                top,
                left,
                width,
                height,
              }}
              data-testid="presence-ring"
              data-session-id={p.sessionId}
              data-stack-index={index}
            />
          );
        }),
      ) : null}

      {/* Layer 3: pseudonym chips, anchored above the focused cell.
          Multiple presences on the same cell stack the chips vertically
          with the most-recently-arrived chip on top (closest to the
          cell). Insertion order is "oldest first" so we render in
          reverse to put the newest at the bottom of the chip stack
          (visually closest to the cell). */}
      {container && containerRect ? Array.from(ringsByCell.entries()).flatMap(
        ([cellKey, list]) => {
          const rect = cellRect(container, list[0]!.position);
          if (!rect) return [];
          const top = rect.top - containerRect.top;
          const left = rect.left - containerRect.left + rect.width / 2;
          // Reverse so most-recent (last in list) renders closest to
          // the cell (smallest negative offset).
          const reversed = [...list].reverse();
          return reversed.map((p, idx) => {
            const player = playersBySessionId.get(p.sessionId);
            if (!player) return null;
            // Chip sits above the cell: bottom edge anchored 4 px above
            // the cell top by `translate(-50%, calc(-100% - 4px))`,
            // then each additional chip rides up 22 px.
            const stackOffset = idx * 22;
            return (
              <div
                key={`chip-${cellKey}-${p.sessionId}`}
                className={chipEl}
                style={{
                  ...playerColorVars(p.sessionId),
                  top,
                  left,
                  transform: `translate(-50%, calc(-100% - 4px - ${stackOffset}px))`,
                }}
                data-testid="presence-chip"
                data-session-id={p.sessionId}
              >
                <span className={chipDot} />
                <span>{player.pseudonym}</span>
              </div>
            );
          });
        },
      ) : null}
    </div>
  );
}

// Helper: extract a cell's bounding rect (or null when the cell is not
// rendered as a `[role="gridcell"]` letter — block / definition / out
// of bounds). Querying off the container scopes the lookup to this
// grid instance even when multiple grids are on the page (test setups).
function cellRect(container: HTMLElement, position: Position): DOMRect | null {
  // The wrapping `[role="gridcell"]` div carries `data-row` /
  // `data-col`; only the inner `<input>` carries `data-cell-kind`. We
  // anchor on the wrapper because it owns the visible cell extent (the
  // input is `pointer-events: none` and centers within it). Filtering
  // on `data-in-word` distinguishes letter wrappers from definition /
  // block wrappers, which never carry that attribute.
  const el = container.querySelector<HTMLElement>(
    `[role="gridcell"][data-row="${position.row}"][data-col="${position.col}"][data-in-word]`,
  );
  return el?.getBoundingClientRect() ?? null;
}

// State reducer for incoming `presenceUpdated` frames. Splitting it out
// keeps the component body readable and makes the dedupe logic
// testable in isolation if a future bug surfaces.
function applyPresenceUpdate(
  event: PresenceUpdatedEvent,
  setPresences: React.Dispatch<React.SetStateAction<readonly PresenceState[]>>,
): void {
  setPresences((current) => {
    const next: PresenceState[] = [];
    let replaced = false;
    for (const p of current) {
      if (p.sessionId === event.sessionId) {
        replaced = true;
        if (event.row !== null && event.column !== null && event.direction !== null) {
          next.push({
            sessionId: event.sessionId,
            position: { row: event.row, col: event.column },
            direction: event.direction,
          });
        }
        // null row/column → drop the entry (peer focused nothing).
      } else {
        next.push(p);
      }
    }
    if (!replaced && event.row !== null && event.column !== null && event.direction !== null) {
      next.push({
        sessionId: event.sessionId,
        position: { row: event.row, col: event.column },
        direction: event.direction,
      });
    }
    return next;
  });
}
