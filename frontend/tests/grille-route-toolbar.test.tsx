import { render } from '@testing-library/react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { PuzzleToolbar } from '@/ui/components/layout/PuzzleToolbar';
import { HintControl } from '@/ui/components/grid/HintControl';
import { useTouchPrimary } from '@/ui/components/keyboard';

function setMatchMedia(matches: boolean) {
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
}

function Harness() {
  const touchPrimary = useTouchPrimary();
  return (
    <PuzzleToolbar
      metadata="Test"
      hintSlot={
        touchPrimary ? undefined : (
          <HintControl
            hintsRemaining={3}
            hintsAllowed={3}
            exhausted={false}
            pending={false}
            lastResult={null}
            errorMessage={null}
            getFocusedCell={() => null}
            onRequest={() => undefined}
          />
        )
      }
    />
  );
}

describe('toolbar hint gating by touch-primary', () => {
  const original = window.matchMedia;
  afterEach(() => {
    window.matchMedia = original;
  });

  it('renders HintControl on desktop', () => {
    setMatchMedia(false);
    const { queryByRole } = render(<Harness />);
    expect(queryByRole('button', { name: /Indice/ })).toBeTruthy();
  });

  it('omits HintControl on touch-primary', () => {
    setMatchMedia(true);
    const { queryByRole } = render(<Harness />);
    expect(queryByRole('button', { name: /Indice/ })).toBeNull();
  });
});
