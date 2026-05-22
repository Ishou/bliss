import { render } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import type { ReactZoomPanPinchContentRef } from 'react-zoom-pan-pinch';
import type { Puzzle } from '@/domain';
import type { Clue } from '@/ui/components/grid/useGridNavigation';
import { MobileKeyboard, TabKeyIcon } from '@/ui/components/keyboard';

const noop = () => undefined;

const stubClue = (text: string, len: number): Clue => ({
  definition: {
    kind: 'definition',
    position: { row: 0, col: 0 },
    clues: [{ text, arrow: 'right' }],
  },
  clue: { text, arrow: 'right' },
  direction: 'across',
  cells: Array.from({ length: len }, (_, i) => ({
    kind: 'letter',
    position: { row: 0, col: i + 1 },
    entry: '',
  })),
});

const stubPuzzle: Puzzle = {
  id: 'test',
  title: 't',
  language: 'fr',
  width: 5,
  height: 5,
  hintsAllowed: 3,
  hintsRemaining: 3,
  cells: [],
};

const fullProps = {
  onLetter: noop,
  onBackspace: noop,
  onToggleDirection: noop,
  onPrevClue: noop,
  onNextClue: noop,
  onRequestHint: noop,
  onMoveCursor: noop as (d: 'left' | 'right' | 'up' | 'down') => void,
  activeClue: stubClue('Fruit', 5) as Clue | null,
  alternateClue: null as Clue | null,
  hintRemaining: 3,
  hintExhausted: false,
  hintPending: false,
  getFocusedCell: () => ({ row: 0, column: 0, isLocked: false }),
  getEntryAt: () => '',
  focusedPosition: null as { row: number; col: number } | null,
  puzzle: stubPuzzle,
  validatedPositions: new Set<string>(),
  transformRef: { current: null } as React.RefObject<ReactZoomPanPinchContentRef | null>,
  scale: 1,
  positionX: 0,
  positionY: 0,
  contentWidth: 200,
  contentHeight: 200,
};

describe('TabKeyIcon', () => {
  const directions = ['previous', 'next'] as const;

  it.each(directions)('renders an inline SVG for direction=%s', (direction) => {
    const { container } = render(<TabKeyIcon direction={direction} />);
    expect(container.querySelector('svg')).toBeTruthy();
  });

  it('uses stroke="currentColor" so the icon inherits the button color', () => {
    const { container } = render(<TabKeyIcon direction="next" />);
    expect(container.querySelector('svg')!.getAttribute('stroke')).toBe('currentColor');
  });

  it('stroke-width matches DirectionArrowIcon at 2.5 for visual consistency', () => {
    const { container } = render(<TabKeyIcon direction="next" />);
    expect(container.querySelector('svg')!.getAttribute('stroke-width')).toBe('2.5');
  });

  it('path includes the perpendicular stop bar at the destination tip (M20 6v12)', () => {
    const { container } = render(<TabKeyIcon direction="next" />);
    const d = container.querySelector('svg path')!.getAttribute('d') ?? '';
    expect(d).toContain('M20 6v12');
  });

  it('previous and next share path geometry; mirroring is via CSS transform', () => {
    const ds = directions.map((d) => {
      const { container } = render(<TabKeyIcon direction={d} />);
      return container.querySelector('svg path')!.getAttribute('d');
    });
    expect(new Set(ds).size).toBe(1);
    const transforms = directions.map((d) => {
      const { container } = render(<TabKeyIcon direction={d} />);
      return (container.querySelector('svg') as SVGElement).style.transform;
    });
    expect(new Set(transforms).size).toBe(2);
  });
});

describe('MobileKeyboard Préc/Suiv buttons', () => {
  const clueNavLabels = ['Indice précédent', 'Indice suivant'] as const;

  it('Préc and Suiv buttons render an SVG, not the old text/unicode labels', () => {
    const { getByLabelText } = render(<MobileKeyboard {...fullProps} />);
    for (const label of clueNavLabels) {
      const btn = getByLabelText(label);
      expect(btn.querySelector('svg')).toBeTruthy();
      const text = btn.textContent ?? '';
      expect(text).not.toMatch(/Préc/);
      expect(text).not.toMatch(/Suiv/);
      for (const glyph of ['◀', '▶']) {
        expect(text).not.toContain(glyph);
      }
    }
  });

  it('Préc and Suiv SVGs include the perpendicular stop bar (M20 6v12)', () => {
    const { getByLabelText } = render(<MobileKeyboard {...fullProps} />);
    for (const label of clueNavLabels) {
      const btn = getByLabelText(label);
      const d = btn.querySelector('svg path')!.getAttribute('d') ?? '';
      expect(d).toContain('M20 6v12');
    }
  });

  it('Préc and Suiv share stroke-width with the cursor arrows for visual coherence', () => {
    const { getByLabelText } = render(<MobileKeyboard {...fullProps} />);
    const labels = [
      'Indice précédent',
      'Indice suivant',
      'Curseur gauche',
      'Curseur haut',
      'Curseur bas',
      'Curseur droite',
    ];
    const widths = labels.map(
      (l) => getByLabelText(l).querySelector('svg')!.getAttribute('stroke-width'),
    );
    expect(new Set(widths).size).toBe(1);
  });
});
