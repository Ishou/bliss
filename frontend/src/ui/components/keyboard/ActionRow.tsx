import { css } from 'styled-system/css';
import type { Puzzle } from '@/domain';
import { KeyboardKey } from './KeyboardKey';
import { KeyboardMinimap } from './KeyboardMinimap';

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
      <KeyboardMinimap
        puzzle={puzzle}
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
