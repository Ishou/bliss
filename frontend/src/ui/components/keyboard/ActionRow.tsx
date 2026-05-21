import { css } from 'styled-system/css';
import type { ReactZoomPanPinchContentRef } from 'react-zoom-pan-pinch';
import type { Puzzle } from '@/domain';
import { GridMinimap } from '@/ui/components/grid/GridMinimap';
import type { Direction } from '@/ui/components/grid/useGridNavigation';
import { KeyboardKey } from './KeyboardKey';

const row = css({
  display: 'flex',
  gap: '4px',
  alignItems: 'center',
  paddingBottom: '4px',
  borderBottom: '1px dashed token(colors.border)',
  marginBottom: '4px',
});

export interface ActionRowProps {
  readonly onPrev: () => void;
  readonly onNext: () => void;
  readonly puzzle: Puzzle;
  readonly validatedPositions: ReadonlySet<string>;
  readonly filledPositions?: ReadonlySet<string>;
  readonly currentWordKeys?: ReadonlySet<string>;
  readonly localCursor?: { position: { row: number; col: number }; direction: Direction } | null;
  readonly transformRef: React.RefObject<ReactZoomPanPinchContentRef | null>;
  readonly scale: number;
  readonly positionX: number;
  readonly positionY: number;
  readonly contentWidth: number;
  readonly contentHeight: number;
}

export function ActionRow({
  onPrev,
  onNext,
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
}: ActionRowProps) {
  return (
    <div className={row}>
      <KeyboardKey
        label="◀ Préc."
        ariaLabel="Indice précédent"
        variant="action"
        onPress={onPrev}
      />
      <GridMinimap
        variant="panel"
        puzzle={puzzle}
        validatedPositions={validatedPositions}
        filledPositions={filledPositions}
        currentWordKeys={currentWordKeys}
        localCursor={localCursor}
        transformRef={transformRef}
        scale={scale}
        positionX={positionX}
        positionY={positionY}
        contentWidth={contentWidth}
        contentHeight={contentHeight}
      />
      <KeyboardKey
        label="Suiv. ▶"
        ariaLabel="Indice suivant"
        variant="action"
        onPress={onNext}
      />
    </div>
  );
}
