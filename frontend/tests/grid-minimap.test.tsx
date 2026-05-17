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
  it('renders nothing at scale 1', () => {
    const { ref } = makeRef();
    const { container } = render(
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
    expect(container.firstChild).toBeNull();
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
});
