import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import type { RatingSubmission, SurveyItem } from '@/application/survey';
import { RatingCard } from '@/ui/components/sondage';

const sampleItem: SurveyItem = {
  itemId: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  mot: 'CHAT',
  definition: 'Animal domestique à moustaches',
  pos: 'nom_commun',
  categorie: 'animals',
  style: 'definition_directe',
  forceClaimed: 2,
  longueur: 4,
  tier: 'mid',
  isCalibration: false,
};

function selectLikertScore(group: HTMLElement, score: number): void {
  const radio = group.querySelector<HTMLButtonElement>(`[role="radio"][aria-label="${score}"]`);
  if (!radio) throw new Error(`radio ${score} not found`);
  act(() => { fireEvent.click(radio); });
}

describe('RatingCard', () => {
  it('renders mot, definition, both likerts, and the flag picker', () => {
    render(<RatingCard item={sampleItem} isAuthenticated={false} onSubmit={() => Promise.resolve()} />);
    expect(screen.getByRole('heading', { name: 'CHAT' })).toBeInTheDocument();
    expect(screen.getByText(/Animal domestique à moustaches/i)).toBeInTheDocument();
    expect(screen.getByRole('radiogroup', { name: 'Qualité' })).toBeInTheDocument();
    expect(screen.getByRole('radiogroup', { name: 'Difficulté' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: /Signaler un problème/i })).toBeInTheDocument();
  });

  it('renders chips with human French labels, not raw enum values', () => {
    const { container } = render(
      <RatingCard item={sampleItem} isAuthenticated={false} onSubmit={() => Promise.resolve()} />,
    );
    const posChip = container.querySelector('[data-chip="pos"]');
    const categorieChip = container.querySelector('[data-chip="categorie"]');
    expect(posChip?.textContent).toBe('Nom commun');
    expect(categorieChip?.textContent).toBe('Animaux');
    expect(screen.getByText(/Style : Définition directe/)).toBeInTheDocument();
    expect(screen.getByText(/Difficulté annoncée : 2/)).toBeInTheDocument();
    expect(screen.queryByText(/nom_commun/i)).toBeNull();
    expect(screen.queryByText(/force annoncée/i)).toBeNull();
  });

  it('hides the CorrectifField when isAuthenticated is false', () => {
    render(<RatingCard item={sampleItem} isAuthenticated={false} onSubmit={() => Promise.resolve()} />);
    expect(screen.queryByLabelText(/Définition alternative/i)).toBeNull();
  });

  it('shows the CorrectifField when isAuthenticated is true', () => {
    render(<RatingCard item={sampleItem} isAuthenticated={true} onSubmit={() => Promise.resolve()} />);
    expect(screen.getByLabelText(/Définition alternative/i)).toBeInTheDocument();
  });

  it('Suivant button stays disabled until both likerts are filled', () => {
    render(<RatingCard item={sampleItem} isAuthenticated={false} onSubmit={() => Promise.resolve()} />);
    const submit = screen.getByRole('button', { name: 'Suivant' });
    expect(submit).toBeDisabled();
    selectLikertScore(screen.getByRole('radiogroup', { name: 'Qualité' }), 4);
    expect(submit).toBeDisabled();
    selectLikertScore(screen.getByRole('radiogroup', { name: 'Difficulté' }), 3);
    expect(submit).toBeEnabled();
  });

  it('submits a payload with both likerts and a non-negative latencyMs', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<RatingCard item={sampleItem} isAuthenticated={false} onSubmit={onSubmit} />);
    selectLikertScore(screen.getByRole('radiogroup', { name: 'Qualité' }), 4);
    selectLikertScore(screen.getByRole('radiogroup', { name: 'Difficulté' }), 2);
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Suivant' }));
    });
    expect(onSubmit).toHaveBeenCalledTimes(1);
    const payload = onSubmit.mock.calls[0][0] as RatingSubmission;
    expect(payload.qualite).toBe(4);
    expect(payload.difficulte).toBe(2);
    expect(payload.latencyMs).toBeGreaterThanOrEqual(0);
    expect(payload.correctif).toBeUndefined();
  });

  it('omits correctif in anon mode even if internal state somehow had one', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<RatingCard item={sampleItem} isAuthenticated={false} onSubmit={onSubmit} />);
    selectLikertScore(screen.getByRole('radiogroup', { name: 'Qualité' }), 1);
    selectLikertScore(screen.getByRole('radiogroup', { name: 'Difficulté' }), 1);
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Suivant' }));
    });
    const payload = onSubmit.mock.calls[0][0] as RatingSubmission;
    expect(payload.correctif).toBeUndefined();
  });
});
