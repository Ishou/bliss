import { css } from 'styled-system/css';
import { KeyboardKey } from './KeyboardKey';

const row = css({
  display: 'flex',
  gap: '4px',
  paddingBottom: '4px',
  borderBottom: '1px dashed token(colors.border)',
  marginBottom: '4px',
});

const hintLabel = css({
  display: 'inline-flex',
  alignItems: 'center',
  gap: '4px',
  fontSize: '12px',
  fontWeight: 'medium',
});

export interface ActionRowProps {
  readonly onPrev: () => void;
  readonly onNext: () => void;
  readonly onHint: () => void;
  readonly hintRemaining: number;
  readonly hintAllowed: number;
  readonly hintDisabled: boolean;
}

export function ActionRow({
  onPrev,
  onNext,
  onHint,
  hintRemaining,
  hintAllowed,
  hintDisabled,
}: ActionRowProps) {
  return (
    <div className={row}>
      <KeyboardKey
        label="◀ Préc."
        ariaLabel="Indice précédent"
        variant="action"
        onPress={onPrev}
      />
      <KeyboardKey
        label={
          <span className={hintLabel}>
            💡 Indice {hintRemaining} / {hintAllowed}
          </span>
        }
        ariaLabel={`Demander un indice, ${hintRemaining} restants`}
        variant="action"
        disabled={hintDisabled}
        onPress={onHint}
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
