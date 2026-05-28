import { act, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { SurveyClient, SurveyContribution } from '@/application/survey';
import { MyContributions } from '@/ui/components/compte';

function stubSurveyClient(getContributions: () => Promise<ReadonlyArray<SurveyContribution>>): SurveyClient {
  return {
    getNextItem: vi.fn(),
    submitRating: vi.fn(),
    getNextPair: vi.fn(),
    submitPairRating: vi.fn(),
    getProgress: vi.fn(),
    getContributions,
    patchPreferences: vi.fn(),
  };
}

describe('MyContributions', () => {
  it('renders the empty-state message when the list is empty', async () => {
    const client = stubSurveyClient(() => Promise.resolve([]));
    await act(async () => { render(<MyContributions surveyClient={client} />); });
    await waitFor(() => expect(screen.getByText(/aucune correction/i)).toBeInTheDocument());
  });

  it('renders one li per contribution with mot, definition, categorie, style, kCoverage', async () => {
    const list: SurveyContribution[] = [
      {
        itemId: '1',
        mot: 'CHAT',
        definition: 'Felin domestique',
        pos: 'nom_commun',
        categorie: 'animals',
        style: 'definition_directe',
        optedOut: false,
        kCoverage: 7,
        createdAt: '2026-05-01T08:00:00Z',
      },
      {
        itemId: '2',
        mot: 'CHIEN',
        definition: 'Meilleur ami',
        pos: 'nom_commun',
        categorie: 'animals',
        style: 'periphrase',
        optedOut: true,
        kCoverage: 3,
        createdAt: '2026-05-02T08:00:00Z',
      },
    ];
    const client = stubSurveyClient(() => Promise.resolve(list));
    await act(async () => { render(<MyContributions surveyClient={client} />); });
    await waitFor(() => expect(screen.getByText('CHAT')).toBeInTheDocument());
    expect(screen.getByText('CHIEN')).toBeInTheDocument();
    expect(screen.getByText(/Felin domestique/)).toBeInTheDocument();
    expect(screen.getByText(/couverture : 7/)).toBeInTheDocument();
    expect(screen.getByText(/sera supprimée en cas de suppression du compte/i)).toBeInTheDocument();
  });

  it('renders an alert when the request fails', async () => {
    const client = stubSurveyClient(() => Promise.reject(new Error('boom')));
    await act(async () => { render(<MyContributions surveyClient={client} />); });
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(/Impossible de charger/i),
    );
  });
});
