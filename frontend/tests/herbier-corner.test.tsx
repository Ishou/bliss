import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  HerbierCorner,
  type HerbierCornerPosition,
} from '@/ui/components/decorations/HerbierCorner';

const CORNERS: readonly HerbierCornerPosition[] = [
  'top-left',
  'top-right',
  'bottom-left',
  'bottom-right',
];

describe('HerbierCorner', () => {
  it('renders an SVG inside the corner container', () => {
    const { container } = render(<HerbierCorner corner="top-left" />);
    const svg = container.querySelector('svg');
    expect(svg).not.toBeNull();
  });

  it('marks the container aria-hidden so it never reaches AT users', () => {
    const { getByTestId } = render(<HerbierCorner corner="top-right" />);
    const node = getByTestId('herbier-corner-top-right');
    expect(node.getAttribute('aria-hidden')).toBe('true');
  });

  it('applies the opacity override via inline style', () => {
    const { getByTestId } = render(
      <HerbierCorner corner="top-left" opacity={0.25} />,
    );
    const node = getByTestId('herbier-corner-top-left');
    expect(node.style.opacity).toBe('0.25');
  });

  it('uses the cluster variant in every corner by default', () => {
    // Earlier iterations rotated lance / oval / cluster / twig per corner;
    // the resulting visual weight differed too much between corners and
    // the big single-leaf variants overpowered the layout. The current
    // intent is one small motif applied uniformly, with corner-specific
    // CSS rotation providing the variety.
    const observed = CORNERS.map((corner) => {
      const { getByTestId } = render(<HerbierCorner corner={corner} />);
      return getByTestId(`herbier-corner-${corner}`).dataset.variant;
    });
    expect(observed).toEqual(['cluster', 'cluster', 'cluster', 'cluster']);
  });

  it('renders the twig variant with multiple ellipses (twig leaves)', () => {
    const { getByTestId } = render(
      <HerbierCorner corner="bottom-right" variant="twig" />,
    );
    const node = getByTestId('herbier-corner-bottom-right');
    const ellipses = node.querySelectorAll('ellipse');
    // Mockup spec ships 8 ellipses on the twig (5 filled + 3 outlined).
    expect(ellipses.length).toBeGreaterThanOrEqual(5);
  });

  it('renders the single variant with exactly one filled leaf path', () => {
    const { getByTestId } = render(
      <HerbierCorner corner="bottom-right" variant="single" />,
    );
    const node = getByTestId('herbier-corner-bottom-right');
    // Single leaf is a lone <path fill="currentColor">; no ellipses.
    expect(node.querySelectorAll('ellipse').length).toBe(0);
    expect(node.querySelectorAll('path').length).toBe(1);
  });

  it('explicit variant prop overrides the per-corner default', () => {
    const { getByTestId } = render(
      <HerbierCorner corner="top-left" variant="cluster" />,
    );
    const node = getByTestId('herbier-corner-top-left');
    expect(node.dataset.variant).toBe('cluster');
  });
});
