import { render, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import type { ReactZoomPanPinchContentRef } from 'react-zoom-pan-pinch';
import type { Puzzle } from '@/domain';
import type { Clue } from '@/ui/components/grid/useGridNavigation';
import { MobileKeyboard } from '@/ui/components/keyboard';

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

describe('MobileKeyboard letters + backspace', () => {
  it('renders all 26 letter buttons', () => {
    const { getAllByRole } = render(<MobileKeyboard {...fullProps} />);
    const buttons = getAllByRole('button');
    const labels = buttons.map((b) => b.getAttribute('aria-label')).filter(Boolean);
    for (const ch of 'ABCDEFGHIJKLMNOPQRSTUVWXYZ') {
      expect(labels).toContain(`Lettre ${ch}`);
    }
  });

  it('clicking a letter calls onLetter with that character', () => {
    const onLetter = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard {...fullProps} onLetter={onLetter} />,
    );
    fireEvent.click(getByLabelText('Lettre E'));
    expect(onLetter).toHaveBeenCalledWith('E');
  });

  it('clicking backspace calls onBackspace', () => {
    const onBackspace = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard {...fullProps} onBackspace={onBackspace} />,
    );
    fireEvent.click(getByLabelText('Effacer'));
    expect(onBackspace).toHaveBeenCalled();
  });

  it('panel has role=group with accessible name', () => {
    const { getByRole } = render(<MobileKeyboard {...fullProps} />);
    expect(getByRole('group', { name: 'Clavier mots fléchés' })).toBeTruthy();
  });

  it('panel root carries touch-action: pan-y class (suppresses pinch, preserves pull-to-refresh)', () => {
    const { getByRole } = render(<MobileKeyboard {...fullProps} />);
    const panel = getByRole('group', { name: 'Clavier mots fléchés' });
    // Panda CSS emits utility classes; touchAction: 'pan-y' compiles to `tch-a_pan-y`.
    expect(panel.className).toMatch(/(^|\s)tch-a_pan-y(\s|$)/);
    expect(panel.className).not.toMatch(/(^|\s)tch-a_none(\s|$)/);
  });

  it('publishes its measured height as --mobile-kb-height on document root', async () => {
    const { unmount } = render(<MobileKeyboard {...fullProps} />);
    await new Promise((r) => requestAnimationFrame(() => r(null)));
    const val = document.documentElement.style.getPropertyValue('--mobile-kb-height');
    expect(val).toMatch(/^\d+px$/);
    unmount();
    expect(document.documentElement.style.getPropertyValue('--mobile-kb-height')).toBe('');
  });

});

describe('MobileKeyboard banner + action row + direction', () => {
  it('shows the clue banner with active clue text', () => {
    const { getByText } = render(<MobileKeyboard {...fullProps} />);
    expect(getByText('Fruit')).toBeTruthy();
  });

  it('does not render a direction-toggle key in the bottom row (handled by banner alt-chip)', () => {
    const { queryByLabelText } = render(<MobileKeyboard {...fullProps} />);
    expect(queryByLabelText('Changer de sens')).toBeNull();
  });

  it('hint button sits in the bottom letter row immediately before the erase key', () => {
    const { getByLabelText, container } = render(<MobileKeyboard {...fullProps} />);
    const hint = getByLabelText(/Demander un indice/);
    const erase = getByLabelText('Effacer');
    expect(hint).toBeTruthy();
    expect(erase).toBeTruthy();
    expect(hint.parentElement).toBe(erase.parentElement);
    const n = getByLabelText('Lettre N');
    expect(n.parentElement).toBe(hint.parentElement);
    const buttons = Array.from(container.querySelectorAll<HTMLElement>('button'));
    expect(buttons.indexOf(hint)).toBe(buttons.indexOf(erase) - 1);
  });

  it('minimap renders inside the keyboard panel', () => {
    const { getByLabelText } = render(<MobileKeyboard {...fullProps} />);
    expect(getByLabelText(/Aperçu de la grille/)).toBeTruthy();
  });

  it('action block is a 6-column 2-row grid; minimap spans cols 1-3 and both rows', () => {
    const { getByLabelText, getByRole } = render(<MobileKeyboard {...fullProps} />);
    const panel = getByRole('group', { name: 'Clavier mots fléchés' });
    const navBlockEl = panel.querySelector(':scope > div:nth-of-type(2)') as HTMLElement | null;
    expect(navBlockEl).toBeTruthy();
    expect(navBlockEl!.className).toMatch(/d_grid/);
    const minimap = getByLabelText(/Aperçu de la grille/);
    expect(minimap.parentElement!.className).toMatch(/grid-c_1_\/_span_3/);
    expect(minimap.parentElement!.className).toMatch(/grid-r_1_\/_span_2/);
  });

  it('action block buttons appear in reading order: Prev, Up, Next, Left, Down, Right', () => {
    const { container, getByLabelText } = render(<MobileKeyboard {...fullProps} />);
    const allButtons = Array.from(container.querySelectorAll<HTMLElement>('button'));
    const actionLabels = [
      'Indice précédent',
      'Curseur haut',
      'Indice suivant',
      'Curseur gauche',
      'Curseur bas',
      'Curseur droite',
    ];
    const indices = actionLabels.map((l) => allButtons.indexOf(getByLabelText(l)));
    for (let i = 1; i < indices.length; i++) {
      expect(indices[i]).toBeGreaterThan(indices[i - 1]);
    }
  });

  it('hint button shows only the lightbulb icon — no "Indice" text and no counter glyph', () => {
    const { getByLabelText } = render(<MobileKeyboard {...fullProps} />);
    const hintBtn = getByLabelText(/Demander un indice/);
    expect(hintBtn.textContent ?? '').not.toMatch(/Indice/);
    expect(hintBtn.textContent ?? '').not.toMatch(/\d/);
    expect(hintBtn.querySelector('svg')).toBeTruthy();
  });

  it('clicking the hint button calls onRequestHint', () => {
    const onRequestHint = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard {...fullProps} onRequestHint={onRequestHint} />,
    );
    fireEvent.click(getByLabelText(/Demander un indice/));
    expect(onRequestHint).toHaveBeenCalled();
  });

  it('clicking Suiv. ▶ calls onNextClue', () => {
    const onNextClue = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard {...fullProps} onNextClue={onNextClue} />,
    );
    fireEvent.click(getByLabelText('Indice suivant'));
    expect(onNextClue).toHaveBeenCalled();
  });

  it('clicking ◀ Préc. calls onPrevClue', () => {
    const onPrevClue = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard {...fullProps} onPrevClue={onPrevClue} />,
    );
    fireEvent.click(getByLabelText('Indice précédent'));
    expect(onPrevClue).toHaveBeenCalled();
  });

  it('hint button disabled when exhausted, pending, or no active clue', () => {
    for (const overrides of [
      { hintExhausted: true },
      { hintPending: true },
      { activeClue: null as unknown as Clue },
    ]) {
      const { getByLabelText, unmount } = render(
        <MobileKeyboard {...fullProps} {...overrides} />,
      );
      const btn = getByLabelText(/Demander un indice/);
      expect(btn.getAttribute('aria-disabled')).toBe('true');
      unmount();
    }
  });

  it('hint click is a no-op when getFocusedCell returns a locked cell', () => {
    const onRequestHint = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard
        {...fullProps}
        getFocusedCell={() => ({ row: 0, column: 0, isLocked: true })}
        onRequestHint={onRequestHint}
      />,
    );
    fireEvent.click(getByLabelText(/Demander un indice/));
    expect(onRequestHint).not.toHaveBeenCalled();
  });

  it('hint click is a no-op when getFocusedCell returns null', () => {
    const onRequestHint = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard
        {...fullProps}
        getFocusedCell={() => null}
        onRequestHint={onRequestHint}
      />,
    );
    fireEvent.click(getByLabelText(/Demander un indice/));
    expect(onRequestHint).not.toHaveBeenCalled();
  });

  it('shows empty-state hint when activeClue is null', () => {
    const { getByText } = render(<MobileKeyboard {...fullProps} activeClue={null} />);
    expect(getByText(/Touchez une case/i)).toBeTruthy();
  });
});

describe('MobileKeyboard viewport zoom reset on mount', () => {
  const BASELINE = 'width=device-width, initial-scale=1.0, viewport-fit=cover';

  function withViewportMeta(content: string): HTMLMetaElement {
    const existing = document.head.querySelector<HTMLMetaElement>('meta[name="viewport"]');
    if (existing) {
      existing.setAttribute('content', content);
      return existing;
    }
    const meta = document.createElement('meta');
    meta.setAttribute('name', 'viewport');
    meta.setAttribute('content', content);
    document.head.appendChild(meta);
    return meta;
  }

  it('briefly sets maximum-scale=1 on mount then restores baseline next frame', async () => {
    const meta = withViewportMeta(BASELINE);
    const rafQueue: FrameRequestCallback[] = [];
    const origRaf = globalThis.requestAnimationFrame;
    const origCancel = globalThis.cancelAnimationFrame;
    globalThis.requestAnimationFrame = ((cb: FrameRequestCallback) => {
      rafQueue.push(cb);
      return rafQueue.length;
    }) as typeof requestAnimationFrame;
    globalThis.cancelAnimationFrame = (() => {}) as typeof cancelAnimationFrame;
    try {
      const { unmount } = render(<MobileKeyboard {...fullProps} />);
      expect(meta.getAttribute('content')).toBe(`${BASELINE}, maximum-scale=1`);
      const cb = rafQueue.shift();
      expect(cb).toBeDefined();
      cb!(performance.now());
      expect(meta.getAttribute('content')).toBe(BASELINE);
      unmount();
    } finally {
      globalThis.requestAnimationFrame = origRaf;
      globalThis.cancelAnimationFrame = origCancel;
      meta.remove();
    }
  });

  it('is a no-op when no viewport meta exists', () => {
    const existing = document.head.querySelector<HTMLMetaElement>('meta[name="viewport"]');
    existing?.remove();
    expect(() => render(<MobileKeyboard {...fullProps} />)).not.toThrow();
  });
});

describe('MobileKeyboard arrow-key row', () => {
  it('renders the 4 cursor-arrow keys with French aria-labels', () => {
    const { getByLabelText } = render(<MobileKeyboard {...fullProps} />);
    expect(getByLabelText('Curseur gauche')).toBeTruthy();
    expect(getByLabelText('Curseur haut')).toBeTruthy();
    expect(getByLabelText('Curseur bas')).toBeTruthy();
    expect(getByLabelText('Curseur droite')).toBeTruthy();
  });

  it('clicking each arrow key calls onMoveCursor with the matching direction', () => {
    const onMoveCursor = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard {...fullProps} onMoveCursor={onMoveCursor} />,
    );
    fireEvent.click(getByLabelText('Curseur gauche'));
    fireEvent.click(getByLabelText('Curseur haut'));
    fireEvent.click(getByLabelText('Curseur bas'));
    fireEvent.click(getByLabelText('Curseur droite'));
    expect(onMoveCursor.mock.calls.map((c) => c[0])).toEqual(['left', 'up', 'down', 'right']);
  });
});
