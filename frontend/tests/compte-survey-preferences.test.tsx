import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { SurveyClient } from '@/application/survey';
import { SurveyPreferences } from '@/ui/components/compte';

function stubSurveyClient(patch: (b: { deleteProposedOnErasure: boolean }) => Promise<void>): SurveyClient {
  return {
    getNextItem: vi.fn(),
    submitRating: vi.fn(),
    getProgress: vi.fn(),
    getContributions: vi.fn(),
    patchPreferences: patch as SurveyClient['patchPreferences'],
  };
}

describe('SurveyPreferences', () => {
  it('renders the checkbox unchecked by default', () => {
    const client = stubSurveyClient(() => Promise.resolve());
    render(<SurveyPreferences surveyClient={client} />);
    expect((screen.getByRole('checkbox') as HTMLInputElement).checked).toBe(false);
  });

  it('PATCHes preferences and shows a "Enregistré" status on success', async () => {
    const patch = vi.fn().mockResolvedValue(undefined);
    const client = stubSurveyClient(patch);
    render(<SurveyPreferences surveyClient={client} />);
    await act(async () => { fireEvent.click(screen.getByRole('checkbox')); });
    expect(patch).toHaveBeenCalledWith({ deleteProposedOnErasure: true });
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(/Enregistré/i));
  });

  it('rolls back the optimistic UI on a generic failure and surfaces a functional alert', async () => {
    const patch = vi.fn().mockRejectedValue(new Error('whatever the backend said'));
    const client = stubSurveyClient(patch);
    render(<SurveyPreferences surveyClient={client} />);
    await act(async () => { fireEvent.click(screen.getByRole('checkbox')); });
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(/Une erreur est survenue/i),
    );
    expect((screen.getByRole('checkbox') as HTMLInputElement).checked).toBe(false);
  });

  it('shows the network-specific copy when the fetch itself fails', async () => {
    // TypeError mirrors what `fetch()` rejects with on CORS / DNS / offline —
    // the exact shape that produced the 2026-05-26 5th-CORS regression.
    const patch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    const client = stubSurveyClient(patch);
    render(<SurveyPreferences surveyClient={client} />);
    await act(async () => { fireEvent.click(screen.getByRole('checkbox')); });
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(/Connexion impossible/i),
    );
    expect(screen.queryByText(/failed to fetch/i)).toBeNull();
  });
});
