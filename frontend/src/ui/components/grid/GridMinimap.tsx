import { useCallback, useMemo, useRef } from 'react';
import { css } from 'styled-system/css';
import type { ReactZoomPanPinchContentRef } from 'react-zoom-pan-pinch';
import type { Position, Puzzle } from '@/domain';
import {
  computeViewportRect,
  rectCenterToPosition,
} from './transformMath';
import { positionKey } from './positionKey';

const MINIMAP_SIZE_DESKTOP_PX = 120;
const MINIMAP_SIZE_MOBILE_PX = 80;

const minimapContainer = css({
  position: 'absolute',
  top: '8px',
  right: '12px',
  width: `${MINIMAP_SIZE_DESKTOP_PX}px`,
  height: `${MINIMAP_SIZE_DESKTOP_PX}px`,
  bg: 'bg.canvas',
  border: '1px solid',
  borderColor: 'gridLine',
  borderRadius: '4px',
  boxShadow: 'sm',
  touchAction: 'none',
  cursor: 'crosshair',
  transition: 'opacity 150ms ease',
  // Re-enable pointer events: the overlayFrame wrapper sets
  // `pointer-events: none` on the containing block so transparent
  // areas don't swallow grid clicks; the minimap itself must respond.
  pointerEvents: 'auto',
  '@media (max-width: 480px)': {
    width: `${MINIMAP_SIZE_MOBILE_PX}px`,
    height: `${MINIMAP_SIZE_MOBILE_PX}px`,
    top: 'auto',
    right: 'auto',
    bottom: '12px',
    left: '8px',
  },
});

// Resolved color literals for SVG fill attributes.
// These map to the role tokens used in Cell.tsx:
//   - block  cells: surfaceMuted = neutral.200 = #e0d8c4
//   - letter cells: surface = #ffffff
//   - definition cells: surfaceVariant = secondary.100 = #fbedd0
//   - validated letter cells: successBg = primary.100 = #dfeacb
const FILL_BLOCK = '#e0d8c4';
const FILL_LETTER = '#ffffff';
const FILL_DEFINITION = '#fbedd0';
const FILL_VALIDATED = '#dfeacb';

export interface GridMinimapProps {
  puzzle: Puzzle;
  validatedPositions: ReadonlySet<string>;
  transformRef: React.RefObject<ReactZoomPanPinchContentRef | null>;
  scale: number;
  positionX: number;
  positionY: number;
  contentWidth: number;
  contentHeight: number;
}

export function GridMinimap({
  puzzle,
  validatedPositions,
  transformRef,
  scale,
  positionX,
  positionY,
  contentWidth,
  contentHeight,
}: GridMinimapProps) {
  // Hooks BEFORE the early-return guard (rules of hooks).
  const minimapRef = useRef<HTMLDivElement | null>(null);
  const dragRef = useRef<{ pointerId: number } | null>(null);

  const cellByKey = useMemo(() => {
    const m = new Map<string, (typeof puzzle.cells)[number]>();
    for (const c of puzzle.cells) m.set(positionKey(c.position), c);
    return m;
  // puzzle.cells is the only thing we read; puzzle identity itself is irrelevant.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [puzzle.cells]);

  const viewBoxW = puzzle.width;
  const viewBoxH = puzzle.height;

  const recenterFromPointer = useCallback(
    (clientX: number, clientY: number) => {
      const el = minimapRef.current;
      const tw = transformRef.current;
      if (!el || !tw) return;
      const box = el.getBoundingClientRect();
      // Translate page-px → minimap-px → viewBox units.
      const localX = clientX - box.left;
      const localY = clientY - box.top;
      const vbX = (localX / box.width) * viewBoxW;
      const vbY = (localY / box.height) * viewBoxH;
      const next = rectCenterToPosition({
        centerX: vbX,
        centerY: vbY,
        scale,
        contentWidth,
        contentHeight,
        minimapWidth: viewBoxW,
        minimapHeight: viewBoxH,
      });
      tw.setTransform(next.positionX, next.positionY, scale, 0);
    },
    [scale, contentWidth, contentHeight, transformRef, viewBoxW, viewBoxH],
  );

  const onPointerDown = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      if (event.button !== 0) return;
      event.preventDefault();
      event.stopPropagation();
      dragRef.current = { pointerId: event.pointerId };
      try {
        (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
      } catch {
        // JSDOM and very old browsers may lack this API.
      }
      recenterFromPointer(event.clientX, event.clientY);
    },
    [recenterFromPointer],
  );

  const onPointerMove = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      const drag = dragRef.current;
      if (!drag || drag.pointerId !== event.pointerId) return;
      recenterFromPointer(event.clientX, event.clientY);
    },
    [recenterFromPointer],
  );

  const onPointerUp = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      const drag = dragRef.current;
      if (!drag || drag.pointerId !== event.pointerId) return;
      dragRef.current = null;
      try {
        (event.currentTarget as HTMLElement).releasePointerCapture(event.pointerId);
      } catch {
        // Already released.
      }
    },
    [],
  );

  // Memoize topology rects: only rebuild when puzzle layout or validated
  // positions change — not on every pan-driven transformState tick.
  const cellRects = useMemo(() => {
    const rects: React.ReactNode[] = [];
    for (let row = 0; row < puzzle.height; row++) {
      for (let col = 0; col < puzzle.width; col++) {
        const k = `${row},${col}`;
        const cell = cellByKey.get(k);
        const kind = cell?.kind ?? 'block';
        const validated = kind === 'letter' && validatedPositions.has(k);
        const fill = validated
          ? FILL_VALIDATED
          : kind === 'block'
          ? FILL_BLOCK
          : kind === 'definition'
          ? FILL_DEFINITION
          : FILL_LETTER;
        rects.push(
          <rect
            key={k}
            data-cell-kind={kind}
            data-row={row}
            data-col={col}
            data-validated={validated || undefined}
            x={col}
            y={row}
            width={1}
            height={1}
            fill={fill}
          />,
        );
      }
    }
    return rects;
  }, [puzzle.height, puzzle.width, cellByKey, validatedPositions]);

  // Early-return AFTER hooks.
  if (scale <= 1.01) return null;

  const rect = computeViewportRect({
    scale,
    positionX,
    positionY,
    contentWidth,
    contentHeight,
    minimapWidth: viewBoxW,
    minimapHeight: viewBoxH,
  });

  return (
    <div
      ref={minimapRef}
      role="img"
      aria-label="Aperçu de la grille — la zone surlignée indique la partie visible"
      className={minimapContainer}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerUp}
    >
      <svg
        viewBox={`0 0 ${viewBoxW} ${viewBoxH}`}
        width="100%"
        height="100%"
        preserveAspectRatio="none"
        style={{ pointerEvents: 'none' }}
        aria-hidden="true"
      >
        {cellRects}
        <rect
          data-role="viewport-rect"
          x={rect.x}
          y={rect.y}
          width={rect.width}
          height={rect.height}
          fill="rgba(212, 204, 184, 0.3)"
          stroke="rgba(212, 204, 184, 0.7)"
          strokeWidth={0.1}
        />
      </svg>
    </div>
  );
}
