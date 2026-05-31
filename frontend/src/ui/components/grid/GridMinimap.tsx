import { useCallback, useMemo, useRef } from 'react';
import { css } from 'styled-system/css';
import type { ReactZoomPanPinchContentRef } from 'react-zoom-pan-pinch';
import type { Puzzle } from '@/domain';
import type { Direction } from './useGridNavigation';
import {
  computeViewportRect,
  rectCenterToPosition,
} from './transformMath';
import { positionKey } from './positionKey';

const MINIMAP_SIZE_DESKTOP_PX = 96;

const overlayContainer = css({
  // Overlay variant: square flex item in the desktop bottom controls bar (alongside zoom).
  position: 'static',
  flexShrink: 0,
  display: 'block',
  width: `${MINIMAP_SIZE_DESKTOP_PX}px`,
  height: `${MINIMAP_SIZE_DESKTOP_PX}px`,
  bg: 'bg.canvas',
  border: '1px solid',
  borderColor: 'gridLine',
  borderRadius: '4px',
  boxShadow: 'sm',
  touchAction: 'none',
  cursor: 'crosshair',
});

const panelContainer = css({
  // Panel variant: fills the minimap cell of the keyboard action block (letterboxed via SVG aspect).
  flex: 1,
  minWidth: 0,
  width: '100%',
  height: '100%',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  bg: 'bg.canvas',
  border: '1px solid token(colors.border)',
  borderRadius: '7px',
  padding: '2px',
  touchAction: 'none',
  cursor: 'crosshair',
});

const panelSvg = css({
  display: 'block',
  width: '100%',
  height: '100%',
});

// Resolved color literals for SVG fill attributes.
// These map to the role tokens used in Cell.tsx:
//   - block  cells: surfaceMuted = neutral.200 = #e0d8c4
//   - letter cells: surface = #ffffff
//   - definition cells: surfaceVariant = secondary.100 = #fbedd0
//   - validated letter cells: successBg = primary.100 = #dfeacb
//   - filled (typed but not validated): a subtle blue-gray wash, chosen
//     to sit clearly between white (empty) and sage (validated) without
//     colliding with the rose in-word tint. Not a named token — comment
//     intentionally captures the design rationale.
//   - in-word cells: pale rose (#fde8e8), deliberately distinct from the
//     honey-amber definition fill (#fbedd0). The live grid uses focusBg
//     (honey) for letter cells, but in the SVG minimap definition cells
//     and in-word letter cells share only color — using the same token
//     would make them indistinguishable. Rose is visually separate from
//     both #fbedd0 (definition) and #dfeacb (validated).
//   - focus-marker stroke: secondary.500 = #c89456 (saturated honey,
//     visible against all four cell fill states)
const FILL_BLOCK = '#e0d8c4';
const FILL_LETTER = '#ffffff';
const FILL_DEFINITION = '#fbedd0';
const FILL_VALIDATED = '#dfeacb';
// Filled-but-not-validated: pale blue-gray, distinct from white/rose/sage.
const FILL_FILLED = '#e0e8f0';
// In-word: pale rose — distinct from definition honey (#fbedd0) in SVG context.
const FILL_IN_WORD = '#fde8e8';
// Focus-marker outline: secondary.500 = saturated honey amber.
const STROKE_FOCUS = '#c89456';

export type GridMinimapVariant = 'overlay' | 'panel';

export interface GridMinimapProps {
  puzzle: Puzzle;
  validatedPositions: ReadonlySet<string>;
  filledPositions?: ReadonlySet<string>;
  currentWordKeys?: ReadonlySet<string>;
  localCursor?: { position: { row: number; col: number }; direction: Direction } | null;
  transformRef: React.RefObject<ReactZoomPanPinchContentRef | null>;
  scale: number;
  positionX: number;
  positionY: number;
  contentWidth: number;
  contentHeight: number;
  // 'overlay' (default) for the desktop bottom bar; 'panel' for the mobile keyboard.
  variant?: GridMinimapVariant;
}

export function GridMinimap({
  puzzle,
  validatedPositions,
  filledPositions,
  currentWordKeys,
  localCursor,
  transformRef,
  scale,
  positionX,
  positionY,
  contentWidth,
  contentHeight,
  variant = 'overlay',
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

  // Memoize topology rects: only rebuild when puzzle layout, validated,
  // filled, or in-word positions change — not on every pan-driven tick.
  //
  // Precedence (lowest → highest):
  //   1. Default by kind (block/definition/letter-white)
  //   2. Filled-but-not-validated (typed, awaiting validation)
  //   3. In-word (current clue word, rose/honey tint)
  //   4. Validated (sage — terminal state, wins over all)
  const cellRects = useMemo(() => {
    const rects: React.ReactNode[] = [];
    for (let row = 0; row < puzzle.height; row++) {
      for (let col = 0; col < puzzle.width; col++) {
        const k = `${row},${col}`;
        const cell = cellByKey.get(k);
        const kind = cell?.kind ?? 'block';
        const isLetter = kind === 'letter';
        const validated = isLetter && validatedPositions.has(k);
        const inWord = isLetter && !validated && (currentWordKeys?.has(k) ?? false);
        const filled = isLetter && !validated && !inWord && (filledPositions?.has(k) ?? false);

        const fill = validated
          ? FILL_VALIDATED
          : inWord
          ? FILL_IN_WORD
          : filled
          ? FILL_FILLED
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
            data-in-word={inWord || undefined}
            data-filled={filled || undefined}
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
  }, [puzzle.height, puzzle.width, cellByKey, validatedPositions, filledPositions, currentWordKeys]);

  // Reserve the flex slot while ResizeObserver hasn't fired yet (dimensions start at 0).
  if (contentWidth <= 0 || contentHeight <= 0) {
    if (variant === 'panel') return <div className={panelContainer} aria-hidden="true" />;
    return null;
  }

  const rect = computeViewportRect({
    scale,
    positionX,
    positionY,
    contentWidth,
    contentHeight,
    minimapWidth: viewBoxW,
    minimapHeight: viewBoxH,
  });

  const isPanel = variant === 'panel';
  const svgProps = isPanel
    ? {
        className: panelSvg,
        preserveAspectRatio: 'xMidYMid meet' as const,
        style: { pointerEvents: 'none' as const },
      }
    : {
        width: '100%' as const,
        height: '100%' as const,
        preserveAspectRatio: 'none' as const,
        style: { pointerEvents: 'none' as const },
      };

  return (
    <div
      ref={minimapRef}
      role="img"
      aria-label="Aperçu de la grille — la zone surlignée indique la partie visible"
      className={isPanel ? panelContainer : overlayContainer}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerUp}
    >
      <svg
        viewBox={`0 0 ${viewBoxW} ${viewBoxH}`}
        {...svgProps}
        aria-hidden="true"
      >
        {cellRects}
        {/* Focus marker: drawn after topology rects, before the viewport
            overlay, so it appears on top of the cell fill but under the
            viewport-rect shadow. Stroke-only so the underlying cell color
            remains visible. */}
        {localCursor && (
          <rect
            data-role="focus-marker"
            x={localCursor.position.col}
            y={localCursor.position.row}
            width={1}
            height={1}
            fill="none"
            stroke={STROKE_FOCUS}
            strokeWidth={0.15}
          />
        )}
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
