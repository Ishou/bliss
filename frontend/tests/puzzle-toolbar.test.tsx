import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PuzzleToolbar } from '@/ui/components/layout';

describe('PuzzleToolbar', () => {
  it('renders the desktop refresh + settings icon buttons with title tooltips', () => {
    render(
      <PuzzleToolbar
        metadata="Grille du jour · n°142 · facile"
        onRefresh={() => {}}
        onOpenSettings={() => {}}
      />,
    );
    const refresh = screen.getByRole('button', { name: 'Actualiser la grille' });
    const settings = screen.getByRole('button', { name: 'Paramètres' });
    expect(refresh).toHaveAttribute('title', 'Actualiser la grille');
    expect(settings).toHaveAttribute('title', 'Paramètres');
  });

  it('exposes the mobile overflow trigger', () => {
    render(
      <PuzzleToolbar
        metadata="Grille du jour"
        onRefresh={vi.fn()}
        onOpenSettings={vi.fn()}
      />,
    );
    expect(
      screen.getByRole('button', { name: "Plus d'actions" }),
    ).toBeInTheDocument();
  });

  it('renders both metadata variants when given a structured shape', () => {
    render(
      <PuzzleToolbar
        metadata={{ short: 'n°142', full: 'Grille du jour · n°142 · facile' }}
      />,
    );
    expect(screen.getByText('n°142')).toBeInTheDocument();
    expect(
      screen.getByText('Grille du jour · n°142 · facile'),
    ).toBeInTheDocument();
  });

  it('renders a single metadata span when given a plain string (no duplicate text node)', () => {
    render(<PuzzleToolbar metadata="Mots fléchés" />);
    expect(screen.getAllByText('Mots fléchés')).toHaveLength(1);
  });

  it('toolbar root carries touch-action: pan-y class when suppressTouchAction is true (suppresses pinch, preserves pull-to-refresh)', () => {
    render(<PuzzleToolbar metadata="n°1 · facile" suppressTouchAction />);
    const toolbar = screen.getByRole('toolbar', { name: 'Outils de la grille' });
    expect(toolbar.className).toMatch(/(^|\s)tch-a_pan-y(\s|$)/);
    expect(toolbar.className).not.toMatch(/(^|\s)tch-a_none(\s|$)/);
  });

  it('toolbar root does not carry the suppress class when suppressTouchAction is absent', () => {
    render(<PuzzleToolbar metadata="n°1 · facile" />);
    const toolbar = screen.getByRole('toolbar', { name: 'Outils de la grille' });
    expect(toolbar.className).not.toMatch(/(^|\s)tch-a_pan-y(\s|$)/);
    expect(toolbar.className).not.toMatch(/(^|\s)tch-a_none(\s|$)/);
  });
});
