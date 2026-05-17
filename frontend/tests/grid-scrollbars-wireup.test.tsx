import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { SAMPLE_PUZZLE } from '@/domain';
import { Grid } from '@/ui/components/grid';

describe('Grid scrollbars + minimap wireup', () => {
  it('does not render scrollbars or minimap at scale 1', () => {
    render(<Grid puzzle={SAMPLE_PUZZLE} />);
    expect(screen.queryByRole('scrollbar')).toBeNull();
    expect(screen.queryByRole('img', { name: /aperçu/i })).toBeNull();
  });

  it('the inner <div role="grid"> has id="puzzle-grid" so aria-controls resolves', () => {
    render(<Grid puzzle={SAMPLE_PUZZLE} />);
    expect(screen.getByRole('grid')).toHaveAttribute('id', 'puzzle-grid');
  });
});
