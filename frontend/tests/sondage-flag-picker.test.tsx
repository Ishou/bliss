import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { FlagPicker } from '@/ui/components/sondage';

async function openListbox(): Promise<void> {
  const trigger = screen.getByRole('combobox', { name: /Signaler un problème/i });
  act(() => { fireEvent.click(trigger); });
  await waitFor(() => {
    expect(trigger).toHaveAttribute('data-state', 'open');
  });
}

describe('FlagPicker', () => {
  it('renders the four reasons plus a "none" option', async () => {
    render(<FlagPicker value={undefined} onChange={() => {}} />);
    await openListbox();
    const options = screen.getAllByRole('option');
    expect(options).toHaveLength(5);
    expect(options[0]).toHaveTextContent('— Aucun —');
  });

  it('reports the chosen reason on selection', async () => {
    const onChange = vi.fn();
    render(<FlagPicker value={undefined} onChange={onChange} />);
    await openListbox();
    act(() => {
      fireEvent.click(screen.getByRole('option', { name: 'Erreur de sens' }));
    });
    await waitFor(() => {
      expect(onChange).toHaveBeenCalledWith('erreur_sens');
    });
  });

  it('reports undefined when the user picks "Aucun"', async () => {
    const onChange = vi.fn();
    render(<FlagPicker value="hors_sujet" onChange={onChange} />);
    await openListbox();
    act(() => {
      fireEvent.click(screen.getByRole('option', { name: '— Aucun —' }));
    });
    await waitFor(() => {
      expect(onChange).toHaveBeenCalledWith(undefined);
    });
  });
});
