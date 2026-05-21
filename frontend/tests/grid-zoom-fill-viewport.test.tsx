import { useRef, useImperativeHandle, type ComponentProps } from 'react';
import { render, screen, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { SAMPLE_PUZZLE } from '@/domain';
import { Grid } from '@/ui/components/grid';

// Module-level scale + callback; each test drives isZoomedIn through _simScale / _fireZoom.
let _simScale = 1;
let _fireZoom: ((scale: number) => void) | null = null;

// Intercepts react-zoom-pan-pinch so state.scale is queryable and onTransform fires synchronously.
vi.mock('react-zoom-pan-pinch', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-zoom-pan-pinch')>();
  const { TransformWrapper: ActualTW } = actual;
  type WrapperProps = ComponentProps<typeof ActualTW>;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  function MockTW({ onTransform, children, ref: externalRef, ...rest }: WrapperProps & { ref?: any }) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const innerRef = useRef<any>(null);
    useImperativeHandle(externalRef, () => ({
      get state() { return { scale: _simScale, positionX: 0, positionY: 0 }; },
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      zoomIn:        (...a: unknown[]) => (innerRef.current as any)?.zoomIn(...a),
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      zoomOut:       (...a: unknown[]) => (innerRef.current as any)?.zoomOut(...a),
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      centerView:    (...a: unknown[]) => (innerRef.current as any)?.centerView(...a),
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      zoomToElement: (...a: unknown[]) => (innerRef.current as any)?.zoomToElement(...a),
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      setTransform:  (...a: unknown[]) => (innerRef.current as any)?.setTransform(...a),
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      resetTransform:(...a: unknown[]) => (innerRef.current as any)?.resetTransform(...a),
    }), []);
    _fireZoom = (scale: number) => {
      _simScale = scale;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      onTransform?.({ state: { scale, positionX: 0, positionY: 0 } } as any, { scale, positionX: 0, positionY: 0 } as any);
    };
    return <ActualTW ref={innerRef} onTransform={onTransform} {...rest}>{children}</ActualTW>;
  }
  return { ...actual, TransformWrapper: MockTW };
});

const matchTouchPrimary = (matches: boolean) => {
  window.matchMedia = vi.fn().mockReturnValue({
    matches,
    media: '(any-pointer: coarse) and (any-hover: none)',
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => true,
  } as MediaQueryList);
};

describe('Grid zoom fills mobile viewport without aspect-ratio letterboxing', () => {
  let originalMM: typeof window.matchMedia;
  let originalRAF: typeof window.requestAnimationFrame;
  beforeEach(() => {
    _simScale = 1;
    _fireZoom = null;
    originalMM = window.matchMedia;
    originalRAF = window.requestAnimationFrame;
    // Synchronous rAF so handleTransform's scale commit resolves before the awaited act.
    window.requestAnimationFrame = (fn: FrameRequestCallback) => { fn(0); return 0; };
  });
  afterEach(() => {
    window.matchMedia = originalMM;
    window.requestAnimationFrame = originalRAF;
  });

  it('keeps the natural aspect-ratio fit at zoom 1 on touch-primary', () => {
    matchTouchPrimary(true);
    render(<Grid puzzle={SAMPLE_PUZZLE} />);
    // At rest the stage preserves aspect-ratio (letterboxed fit, no fill behaviour).
    const stage = screen.getByTestId('grid-stage');
    expect(stage.style.aspectRatio).toBe(`${SAMPLE_PUZZLE.width} / ${SAMPLE_PUZZLE.height}`);
  });

  it('drops the aspect-ratio constraint and expands the stage to fill the shell when zoomed on touch-primary', async () => {
    matchTouchPrimary(true);
    render(<Grid puzzle={SAMPLE_PUZZLE} />);
    await act(async () => { _fireZoom?.(1.5); });
    const stage = screen.getByTestId('grid-stage');
    // Aspect-ratio constraint gone → no letterboxing inside the shell rect.
    expect(stage.style.aspectRatio).toBe('');
    // Stage now sizes to the full container-query rect (the visible shell).
    expect(stage.style.width).toBe('100cqw');
    expect(stage.style.height).toBe('100cqh');
  });

  it('marks the grid frame as "fill shell" when zoomed on touch-primary', async () => {
    matchTouchPrimary(true);
    const { container } = render(<Grid puzzle={SAMPLE_PUZZLE} />);
    await act(async () => { _fireZoom?.(1.5); });
    // data-fill-shell is the testable surface; jsdom drops container-query values from style.
    const gridFrame = container.querySelector('[role="grid"]')?.parentElement;
    expect(gridFrame).not.toBeNull();
    expect(gridFrame!).toHaveAttribute('data-fill-shell', 'true');
  });

  it('does not mark the grid frame as "fill shell" at zoom 1 on touch-primary', () => {
    matchTouchPrimary(true);
    const { container } = render(<Grid puzzle={SAMPLE_PUZZLE} />);
    const gridFrame = container.querySelector('[role="grid"]')?.parentElement;
    expect(gridFrame!).not.toHaveAttribute('data-fill-shell');
  });

  it('does not mark the grid frame as "fill shell" when zoomed on desktop', async () => {
    matchTouchPrimary(false);
    const { container } = render(<Grid puzzle={SAMPLE_PUZZLE} />);
    await act(async () => { _fireZoom?.(1.5); });
    const gridFrame = container.querySelector('[role="grid"]')?.parentElement;
    expect(gridFrame!).not.toHaveAttribute('data-fill-shell');
  });

  it('keeps the desktop (non-touch) zoom behaviour unchanged', async () => {
    matchTouchPrimary(false);
    render(<Grid puzzle={SAMPLE_PUZZLE} />);
    await act(async () => { _fireZoom?.(1.5); });
    const stage = screen.getByTestId('grid-stage');
    // Desktop: aspect-ratio remains pinned (no viewport-fill behaviour).
    expect(stage.style.aspectRatio).toBe(`${SAMPLE_PUZZLE.width} / ${SAMPLE_PUZZLE.height}`);
  });
});

