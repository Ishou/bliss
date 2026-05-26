import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { FlagPicker } from '@/ui/components/sondage';

describe('FlagPicker', () => {
  it('renders the four reasons plus a "none" option', () => {
    render(<FlagPicker value={undefined} onChange={() => {}} />);
    const select = screen.getByLabelText(/Signaler un problème/i);
    expect(select).toBeInTheDocument();
    const options = (select as HTMLSelectElement).querySelectorAll('option');
    expect(options).toHaveLength(5);
  });

  it('reports the chosen reason on change', () => {
    const onChange = vi.fn();
    render(<FlagPicker value={undefined} onChange={onChange} />);
    fireEvent.change(screen.getByLabelText(/Signaler un problème/i), {
      target: { value: 'erreur_sens' },
    });
    expect(onChange).toHaveBeenCalledWith('erreur_sens');
  });

  it('reports undefined when the user picks "Aucun"', () => {
    const onChange = vi.fn();
    render(<FlagPicker value="hors_sujet" onChange={onChange} />);
    fireEvent.change(screen.getByLabelText(/Signaler un problème/i), {
      target: { value: '' },
    });
    expect(onChange).toHaveBeenCalledWith(undefined);
  });
});
