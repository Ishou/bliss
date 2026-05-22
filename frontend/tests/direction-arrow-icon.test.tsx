import { render } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import type { ReactZoomPanPinchContentRef } from 'react-zoom-pan-pinch';
import type { Puzzle } from '@/domain';
import type { Clue } from '@/ui/components/grid/useGridNavigation';
import { DirectionArrowIcon, MobileKeyboard } from '@/ui/components/keyboard';

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

describe('DirectionArrowIcon', () => {
  const directions = ['left', 'right', 'up', 'down'] as const;

  it.each(directions)('renders an inline SVG for direction=%s', (direction) => {
    const { container } = render(<DirectionArrowIcon direction={direction} />);
    expect(container.querySelector('svg')).toBeTruthy();
  });

  it('uses stroke="currentColor" so the icon inherits the button color', () => {
    const { container } = render(<DirectionArrowIcon direction="right" />);
    expect(container.querySelector('svg')!.getAttribute('stroke')).toBe('currentColor');
  });

  it('all four directions render with the same stroke-width', () => {
    const widths = directions.map((d) => {
      const { container } = render(<DirectionArrowIcon direction={d} />);
      return container.querySelector('svg')!.getAttribute('stroke-width');
    });
    expect(new Set(widths).size).toBe(1);
    expect(widths[0]).toBe('2.5');
  });

  it('all four directions share the same path geometry (rotation drives orientation)', () => {
    const ds = directions.map((d) => {
      const { container } = render(<DirectionArrowIcon direction={d} />);
      return container.querySelector('svg path')!.getAttribute('d');
    });
    expect(new Set(ds).size).toBe(1);
  });

  it('each direction applies a distinct CSS rotation', () => {
    const transforms = directions.map((d) => {
      const { container } = render(<DirectionArrowIcon direction={d} />);
      return (container.querySelector('svg') as SVGElement).style.transform;
    });
    expect(new Set(transforms).size).toBe(4);
  });
});

describe('MobileKeyboard direction-arrow buttons', () => {
  const arrowLabels = [
    'Curseur gauche',
    'Curseur haut',
    'Curseur bas',
    'Curseur droite',
  ] as const;

  it('each of the 4 arrow buttons renders an SVG, not a unicode glyph', () => {
    const { getByLabelText } = render(<MobileKeyboard {...fullProps} />);
    for (const label of arrowLabels) {
      const btn = getByLabelText(label);
      expect(btn.querySelector('svg')).toBeTruthy();
      const text = btn.textContent ?? '';
      for (const glyph of ['←', '↑', '→', '↓']) {
        expect(text).not.toContain(glyph);
      }
    }
  });

  it('all 4 arrow buttons share an identical stroke-width attribute', () => {
    const { getByLabelText } = render(<MobileKeyboard {...fullProps} />);
    const widths = arrowLabels.map(
      (l) => getByLabelText(l).querySelector('svg')!.getAttribute('stroke-width'),
    );
    expect(new Set(widths).size).toBe(1);
  });
});
