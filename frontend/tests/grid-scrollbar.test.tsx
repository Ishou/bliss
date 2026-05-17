import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import type { ReactZoomPanPinchContentRef } from 'react-zoom-pan-pinch';
import { GridScrollbar } from '@/ui/components/grid/GridScrollbar';

const makeRef = (overrides?: Partial<ReactZoomPanPinchContentRef['state']>) => {
  const setTransform = vi.fn();
  const ref = {
    current: {
      state: { scale: 1, positionX: 0, positionY: 0, ...overrides } as ReactZoomPanPinchContentRef['state'],
      setTransform,
    } as unknown as ReactZoomPanPinchContentRef,
  };
  return { ref, setTransform };
};

describe('GridScrollbar', () => {
  it('renders nothing at scale 1', () => {
    const { ref } = makeRef({ scale: 1 });
    const { container } = render(
      <GridScrollbar
        orientation="vertical"
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

  it('renders a vertical scrollbar with role=scrollbar at scale > 1', () => {
    const { ref } = makeRef({ scale: 2 });
    render(
      <GridScrollbar
        orientation="vertical"
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const bar = screen.getByRole('scrollbar', { name: /vertical/i });
    expect(bar).toHaveAttribute('aria-orientation', 'vertical');
    expect(bar).toHaveAttribute('aria-controls', 'puzzle-grid');
    expect(bar).toHaveAttribute('aria-valuemin', '0');
    expect(bar).toHaveAttribute('aria-valuemax', '100');
  });

  it('renders a horizontal scrollbar with aria-orientation=horizontal', () => {
    const { ref } = makeRef({ scale: 2 });
    render(
      <GridScrollbar
        orientation="horizontal"
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const bar = screen.getByRole('scrollbar', { name: /horizontal/i });
    expect(bar).toHaveAttribute('aria-orientation', 'horizontal');
  });

  it('aria-valuenow reflects scroll progress (0 at start, 100 at end)', () => {
    const { ref } = makeRef({ scale: 2 });
    const { rerender } = render(
      <GridScrollbar
        orientation="vertical"
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    let bar = screen.getByRole('scrollbar');
    expect(bar.getAttribute('aria-valuenow')).toBe('0');

    rerender(
      <GridScrollbar
        orientation="vertical"
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={-200}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    bar = screen.getByRole('scrollbar');
    expect(bar.getAttribute('aria-valuenow')).toBe('100');
  });

  it('drags the thumb and calls setTransform with the new position', () => {
    const { ref, setTransform } = makeRef({ scale: 2 });
    render(
      <GridScrollbar
        orientation="vertical"
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const thumb = screen.getByTestId('grid-scrollbar-thumb-vertical');

    const trackEl = screen.getByRole('scrollbar');
    trackEl.getBoundingClientRect = () => ({
      x: 100, y: 0, top: 0, left: 100, right: 108, bottom: 200,
      width: 8, height: 200, toJSON: () => ({}),
    });
    thumb.getBoundingClientRect = () => ({
      x: 100, y: 0, top: 0, left: 100, right: 108, bottom: 100,
      width: 8, height: 100, toJSON: () => ({}),
    });

    thumb.dispatchEvent(new PointerEvent('pointerdown', {
      bubbles: true, clientX: 104, clientY: 50, pointerId: 1, button: 0,
    }));
    thumb.dispatchEvent(new PointerEvent('pointermove', {
      bubbles: true, clientX: 104, clientY: 75, pointerId: 1,
    }));
    thumb.dispatchEvent(new PointerEvent('pointerup', {
      bubbles: true, clientX: 104, clientY: 75, pointerId: 1,
    }));

    expect(setTransform).toHaveBeenCalled();
    const lastCall = setTransform.mock.calls.at(-1);
    expect(lastCall?.[0]).toBe(0);
    expect(lastCall?.[1]).toBeCloseTo(-50, 1);
    expect(lastCall?.[2]).toBe(2);
  });
});
