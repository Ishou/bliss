import { render, fireEvent, screen } from '@testing-library/react';
import { describe, it, expect, afterEach, vi } from 'vitest';
import type { Cell, Puzzle } from '@/domain';
import { Grid } from '@/ui/components/grid';
import { CurrentCluePanel } from '@/ui/components/grid/CurrentCluePanel';
import type { Clue } from '@/ui/components/grid/useGridNavigation';

// 5×4 grid mirroring tests/grid-input.test.tsx so focus + clue lookup
// behave identically. Cell (1,1) sits on `across-2`; cell (1,2) is the
// intersection of `across-2` and `down-1`.
const L = (row: number, col: number): Cell =>
  ({ kind: 'letter', position: { row, col }, entry: '' });

const TEST_PUZZLE: Puzzle = {
  id: 'test', title: 'test', language: 'fr', width: 5, height: 4, hintsAllowed: 3,
  cells: [
    { kind: 'definition', position: { row: 0, col: 0 }, clues: [{ text: 'across-1', arrow: 'right' }] },
    L(0, 1),
    { kind: 'definition', position: { row: 0, col: 2 }, clues: [{ text: 'down-1', arrow: 'down' }] },
    L(0, 3), L(0, 4),
    { kind: 'definition', position: { row: 1, col: 0 }, clues: [{ text: 'across-2', arrow: 'right' }] },
    L(1, 1), L(1, 2), L(1, 3), L(1, 4),
    L(2, 0), L(2, 1), L(2, 2), L(2, 3), L(2, 4),
    L(3, 0),
    { kind: 'block', position: { row: 3, col: 1 } },
    L(3, 2), L(3, 3), L(3, 4),
  ],
};

const inputAt = (root: HTMLElement, row: number, col: number) =>
  root.querySelector<HTMLInputElement>(`[data-cell-kind="letter"][data-row="${row}"][data-col="${col}"]`);
// jsdom doesn't auto-focus inputs on click; explicit el.focus() before
// the click mirrors the browser's default action that production leans
// on (we no longer call `focusCell` from the click handler — see
// useGridNavigation.ts).
const click = (el: HTMLElement) => { el.focus(); fireEvent.click(el); };

describe('CurrentCluePanel (standalone)', () => {
  it('shows a placeholder when no clue is selected', () => {
    render(<CurrentCluePanel clue={null} />);
    expect(
      screen.getByText(/sélectionnez une case pour afficher la définition/i),
    ).toBeInTheDocument();
  });

  it('renders clue text in full plus the arrow direction glyph', () => {
    const clue: Clue = {
      definition: {
        kind: 'definition', position: { row: 0, col: 0 },
        clues: [{ text: 'Astre nocturne', arrow: 'right' }],
      },
      clue: { text: 'Astre nocturne', arrow: 'right' },
      direction: 'across',
      cells: [],
    };
    render(<CurrentCluePanel clue={clue} />);
    expect(screen.getByText('Astre nocturne')).toBeInTheDocument();
    expect(screen.getByLabelText('définition horizontale')).toBeInTheDocument();
  });
});

describe('CurrentCluePanel (wired into Grid)', () => {
  it('updates when a letter cell is focused', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    // Initial render: panel shows placeholder.
    expect(
      screen.getByText(/sélectionnez une case pour afficher la définition/i),
    ).toBeInTheDocument();
    // Focus a cell on `across-2`. Panel reflects the active across clue.
    const target = inputAt(container, 1, 1)!;
    click(target);
    const panel = screen.getByTestId('current-clue-panel');
    expect(panel).toHaveTextContent('across-2');
    expect(panel.querySelector('[aria-label="définition horizontale"]')).not.toBeNull();
  });
});

// `window.visualViewport` lets the panel survive mobile pinch-zoom — sticky
// alone pins to the layout viewport, which scrolls off when the user pans
// the zoomed visual viewport. The hook applies a `transform` that keeps the
// panel anchored to the visible top at a constant on-screen size.
describe('CurrentCluePanel (visual viewport)', () => {
  afterEach(() => {
    // jsdom's default has no `visualViewport`; restore that between tests
    // so the absent-API test isn't polluted by a previous mock.
    Object.defineProperty(window, 'visualViewport', {
      configurable: true,
      value: undefined,
    });
  });

  it('switches to fixed positioning + transform when the visual viewport is zoomed', () => {
    Object.defineProperty(window, 'visualViewport', {
      configurable: true,
      value: {
        scale: 2,
        offsetLeft: 30,
        offsetTop: 50,
        width: 180,
        height: 320,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      },
    });
    render(<CurrentCluePanel clue={null} />);
    const panel = screen.getByTestId('current-clue-panel');
    // `fixed` overrides the Panda `position: sticky` so the panel anchors
    // to the layout viewport top regardless of its natural flow position.
    expect(panel.style.position).toBe('fixed');
    expect(panel.style.top).toBe('0px');
    expect(panel.style.left).toBe('0px');
    expect(panel.style.right).toBe('0px');
    expect(panel.style.transform).toBe('translate(30px, 50px) scale(0.5)');
    expect(panel.style.transformOrigin).toBe('top left');
  });

  it('leaves the inline overrides empty when window.visualViewport is unsupported', () => {
    // jsdom default — `window.visualViewport === undefined`.
    expect(window.visualViewport).toBeUndefined();
    render(<CurrentCluePanel clue={null} />);
    const panel = screen.getByTestId('current-clue-panel');
    expect(panel.style.position).toBe('');
    expect(panel.style.transform).toBe('');
    expect(panel.style.transformOrigin).toBe('');
  });
});
