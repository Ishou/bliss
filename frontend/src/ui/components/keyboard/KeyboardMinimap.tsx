import { useMemo } from 'react';
import { css } from 'styled-system/css';
import type { Puzzle } from '@/domain';
import { computeViewportRect } from '@/ui/components/grid/transformMath';
import { positionKey } from '@/ui/components/grid/positionKey';

// Resolved color literals — mirror GridMinimap.tsx (kept in sync intentionally).
const FILL_BLOCK = '#e0d8c4';
const FILL_LETTER = '#ffffff';
const FILL_DEFINITION = '#fbedd0';

const container = css({
  flex: 1,
  minWidth: 0,
  maxWidth: '160px',
  height: '44px',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  bg: 'bg.canvas',
  border: '1px solid token(colors.border)',
  borderRadius: '7px',
  padding: '2px',
});

const placeholder = css({
  flex: 1,
  minWidth: 0,
  maxWidth: '160px',
  height: '44px',
});

const svgStyle = css({
  display: 'block',
  height: '100%',
  width: 'auto',
  maxWidth: '100%',
});

export interface KeyboardMinimapProps {
  readonly puzzle: Puzzle;
  readonly scale: number;
  readonly positionX: number;
  readonly positionY: number;
  readonly contentWidth: number;
  readonly contentHeight: number;
}

export function KeyboardMinimap({
  puzzle,
  scale,
  positionX,
  positionY,
  contentWidth,
  contentHeight,
}: KeyboardMinimapProps) {
  const cellByKey = useMemo(() => {
    const m = new Map<string, (typeof puzzle.cells)[number]>();
    for (const c of puzzle.cells) m.set(positionKey(c.position), c);
    return m;
    // Only puzzle.cells is read; puzzle identity itself is irrelevant.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [puzzle.cells]);

  const cellRects = useMemo(() => {
    const rects: React.ReactNode[] = [];
    for (let row = 0; row < puzzle.height; row++) {
      for (let col = 0; col < puzzle.width; col++) {
        const cell = cellByKey.get(`${row},${col}`);
        const kind = cell?.kind ?? 'block';
        const fill =
          kind === 'block' ? FILL_BLOCK : kind === 'definition' ? FILL_DEFINITION : FILL_LETTER;
        rects.push(
          <rect key={`${row},${col}`} x={col} y={row} width={1} height={1} fill={fill} />,
        );
      }
    }
    return rects;
  }, [cellByKey, puzzle.height, puzzle.width]);

  // Reserve the same slot when at rest so the action row's layout doesn't jump on zoom-in.
  if (scale <= 1.01 || contentWidth <= 0 || contentHeight <= 0) {
    return <div className={placeholder} aria-hidden="true" />;
  }

  const viewBoxW = puzzle.width;
  const viewBoxH = puzzle.height;
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
      className={container}
      role="img"
      aria-label="Aperçu de la grille — la zone surlignée indique la partie visible"
    >
      <svg
        className={svgStyle}
        viewBox={`0 0 ${viewBoxW} ${viewBoxH}`}
        preserveAspectRatio="xMidYMid meet"
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
