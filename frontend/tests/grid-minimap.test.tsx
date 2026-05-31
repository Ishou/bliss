import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import type { ReactZoomPanPinchContentRef } from 'react-zoom-pan-pinch';
import { SAMPLE_PUZZLE } from '@/domain';
import { GridMinimap } from '@/ui/components/grid/GridMinimap';

const makeRef = () => {
  const setTransform = vi.fn();
  const ref = {
    current: {
      state: { scale: 2, positionX: 0, positionY: 0 } as ReactZoomPanPinchContentRef['state'],
      setTransform,
    } as unknown as ReactZoomPanPinchContentRef,
  };
  return { ref, setTransform };
};

describe('GridMinimap', () => {
  it('overlay variant renders at scale 1 (always-on overview)', () => {
    const { ref } = makeRef();
    render(
      <GridMinimap
        puzzle={SAMPLE_PUZZLE}
        validatedPositions={new Set()}
        transformRef={ref}
        scale={1}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    expect(screen.getByRole('img', { name: /aperçu de la grille/i })).toBeInTheDocument();
  });

  it('renders an accessible img with one rect per cell at scale > 1', () => {
    const { ref } = makeRef();
    const { container } = render(
      <GridMinimap
        puzzle={SAMPLE_PUZZLE}
        validatedPositions={new Set()}
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const minimap = screen.getByRole('img', { name: /aperçu de la grille/i });
    expect(minimap).toBeInTheDocument();
    const rects = container.querySelectorAll('rect[data-cell-kind]');
    const expected = SAMPLE_PUZZLE.width * SAMPLE_PUZZLE.height;
    expect(rects.length).toBe(expected);
    expect(container.querySelectorAll('rect[data-role="viewport-rect"]')).toHaveLength(1);
  });

  it('pointer-down recenters the viewport on the pointer location', () => {
    const { ref, setTransform } = makeRef();
    render(
      <GridMinimap
        puzzle={SAMPLE_PUZZLE}
        validatedPositions={new Set()}
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const minimap = screen.getByRole('img', { name: /aperçu de la grille/i });
    minimap.getBoundingClientRect = () => ({
      x: 0, y: 0, top: 0, left: 0, right: 80, bottom: 80,
      width: 80, height: 80, toJSON: () => ({}),
    });

    minimap.dispatchEvent(new PointerEvent('pointerdown', {
      bubbles: true, clientX: 40, clientY: 40, pointerId: 1, button: 0,
    }));

    expect(setTransform).toHaveBeenCalledTimes(1);
    const args = setTransform.mock.calls[0];
    expect(args[0]).toBeCloseTo(-100, 1);
    expect(args[1]).toBeCloseTo(-100, 1);
    expect(args[2]).toBe(2);
  });

  it('pointer-move while held continues to re-center', () => {
    const { ref, setTransform } = makeRef();
    render(
      <GridMinimap
        puzzle={SAMPLE_PUZZLE}
        validatedPositions={new Set()}
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const minimap = screen.getByRole('img', { name: /aperçu de la grille/i });
    minimap.getBoundingClientRect = () => ({
      x: 0, y: 0, top: 0, left: 0, right: 80, bottom: 80,
      width: 80, height: 80, toJSON: () => ({}),
    });

    minimap.dispatchEvent(new PointerEvent('pointerdown', {
      bubbles: true, clientX: 20, clientY: 20, pointerId: 1, button: 0,
    }));
    minimap.dispatchEvent(new PointerEvent('pointermove', {
      bubbles: true, clientX: 60, clientY: 60, pointerId: 1,
    }));
    minimap.dispatchEvent(new PointerEvent('pointerup', {
      bubbles: true, clientX: 60, clientY: 60, pointerId: 1,
    }));

    expect(setTransform).toHaveBeenCalledTimes(2);
    const second = setTransform.mock.calls[1];
    expect(second[0]).toBe(-200);
    expect(second[1]).toBe(-200);
  });

  it('validated letter cells render with a data-validated attribute', () => {
    const { ref } = makeRef();
    const letterCell = SAMPLE_PUZZLE.cells.find((c) => c.kind === 'letter');
    if (!letterCell) throw new Error('sample puzzle has no letter cell');
    const key = `${letterCell.position.row},${letterCell.position.col}`;
    const { container } = render(
      <GridMinimap
        puzzle={SAMPLE_PUZZLE}
        validatedPositions={new Set([key])}
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const rect = container.querySelector(
      `rect[data-cell-kind="letter"][data-row="${letterCell.position.row}"][data-col="${letterCell.position.col}"]`,
    );
    expect(rect).toHaveAttribute('data-validated', 'true');
  });

  it('renders in-word cells with data-in-word="true"', () => {
    const { ref } = makeRef();
    const letterCell = SAMPLE_PUZZLE.cells.find((c) => c.kind === 'letter');
    if (!letterCell) throw new Error('sample puzzle has no letter cell');
    const key = `${letterCell.position.row},${letterCell.position.col}`;
    const { container } = render(
      <GridMinimap
        puzzle={SAMPLE_PUZZLE}
        validatedPositions={new Set()}
        currentWordKeys={new Set([key])}
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const rect = container.querySelector(
      `rect[data-cell-kind="letter"][data-row="${letterCell.position.row}"][data-col="${letterCell.position.col}"]`,
    );
    expect(rect).toHaveAttribute('data-in-word', 'true');
  });

  it('renders filled cells with data-filled="true"', () => {
    const { ref } = makeRef();
    const letterCell = SAMPLE_PUZZLE.cells.find((c) => c.kind === 'letter');
    if (!letterCell) throw new Error('sample puzzle has no letter cell');
    const key = `${letterCell.position.row},${letterCell.position.col}`;
    const { container } = render(
      <GridMinimap
        puzzle={SAMPLE_PUZZLE}
        validatedPositions={new Set()}
        filledPositions={new Set([key])}
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const rect = container.querySelector(
      `rect[data-cell-kind="letter"][data-row="${letterCell.position.row}"][data-col="${letterCell.position.col}"]`,
    );
    expect(rect).toHaveAttribute('data-filled', 'true');
  });

  it('precedence: validated wins over in-word', () => {
    const { ref } = makeRef();
    const letterCell = SAMPLE_PUZZLE.cells.find((c) => c.kind === 'letter');
    if (!letterCell) throw new Error('sample puzzle has no letter cell');
    const key = `${letterCell.position.row},${letterCell.position.col}`;
    const { container } = render(
      <GridMinimap
        puzzle={SAMPLE_PUZZLE}
        validatedPositions={new Set([key])}
        currentWordKeys={new Set([key])}
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const rect = container.querySelector(
      `rect[data-cell-kind="letter"][data-row="${letterCell.position.row}"][data-col="${letterCell.position.col}"]`,
    );
    expect(rect).toHaveAttribute('data-validated', 'true');
    expect(rect).not.toHaveAttribute('data-in-word');
  });

  it('panel variant renders at scale 1 (always-on position indicator)', () => {
    const { ref } = makeRef();
    const { container } = render(
      <GridMinimap
        variant="panel"
        puzzle={SAMPLE_PUZZLE}
        validatedPositions={new Set()}
        transformRef={ref}
        scale={1}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const minimap = screen.getByRole('img', { name: /aperçu de la grille/i });
    expect(minimap).toBeInTheDocument();
    const rects = container.querySelectorAll('rect[data-cell-kind]');
    expect(rects.length).toBe(SAMPLE_PUZZLE.width * SAMPLE_PUZZLE.height);
  });

  it('panel variant tap-to-pan invokes setTransform when zoomed in', () => {
    const { ref, setTransform } = makeRef();
    render(
      <GridMinimap
        variant="panel"
        puzzle={SAMPLE_PUZZLE}
        validatedPositions={new Set()}
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const minimap = screen.getByRole('img', { name: /aperçu de la grille/i });
    minimap.getBoundingClientRect = () => ({
      x: 0, y: 0, top: 0, left: 0, right: 120, bottom: 44,
      width: 120, height: 44, toJSON: () => ({}),
    });
    minimap.dispatchEvent(new PointerEvent('pointerdown', {
      bubbles: true, clientX: 60, clientY: 22, pointerId: 1, button: 0,
    }));
    expect(setTransform).toHaveBeenCalledTimes(1);
  });

  it('renders a focus-marker rect at the localCursor position', () => {
    const { ref } = makeRef();
    const letterCell = SAMPLE_PUZZLE.cells.find((c) => c.kind === 'letter');
    if (!letterCell) throw new Error('sample puzzle has no letter cell');
    const { container } = render(
      <GridMinimap
        puzzle={SAMPLE_PUZZLE}
        validatedPositions={new Set()}
        localCursor={{ position: letterCell.position, direction: 'across' }}
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const marker = container.querySelector('rect[data-role="focus-marker"]');
    expect(marker).toBeInTheDocument();
    expect(marker).toHaveAttribute('x', String(letterCell.position.col));
    expect(marker).toHaveAttribute('y', String(letterCell.position.row));
  });
});
