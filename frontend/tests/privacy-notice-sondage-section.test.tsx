import { render, screen } from '@testing-library/react';
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router';
import { describe, expect, it, vi } from 'vitest';
import { PrivacyNotice } from '@/ui/components/PrivacyNotice';
import type { SessionClient } from '@/application/session/SessionClient';

function renderFrenchNotice() {
  const sessionClient: SessionClient = {
    eraseSession: vi.fn(async () => ({ deleted: 0 })),
    getSessionId: vi.fn(() => 'session-id'),
    clearLocalSession: vi.fn(),
  };
  const rootRoute = createRootRoute();
  const route = createRoute({
    getParentRoute: () => rootRoute,
    path: '/confidentialite',
    component: () => <PrivacyNotice lang="fr" sessionClient={sessionClient} />,
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([route]),
    history: createMemoryHistory({ initialEntries: ['/confidentialite'] }),
  });
  return render(<RouterProvider router={router} />);
}

describe('PrivacyNotice — sondage RGPD section (FR)', () => {
  it('renders the sondage heading as an h2', async () => {
    renderFrenchNotice();
    const heading = await screen.findByRole('heading', {
      level: 2,
      name: 'Sondage de qualité des définitions',
    });
    expect(heading).toBeTruthy();
  });

  it('renders both h3 sub-sections', async () => {
    renderFrenchNotice();
    expect(
      await screen.findByRole('heading', {
        level: 3,
        name: 'Anonymisation à la suppression du compte',
      }),
    ).toBeTruthy();
    expect(
      screen.getByRole('heading', { level: 3, name: 'Corrections proposées' }),
    ).toBeTruthy();
  });

  it('links to /compte for the deleteProposedOnErasure toggle', async () => {
    renderFrenchNotice();
    const link = await screen.findByRole('link', { name: 'Mon compte' });
    expect(link.getAttribute('href')).toBe('/compte');
  });

  it('names the exact /compte toggle copy verbatim', async () => {
    renderFrenchNotice();
    const matches = await screen.findAllByText(
      /Supprimer aussi mes corrections proposées en cas de suppression de mon compte/,
    );
    expect(matches.length).toBeGreaterThan(0);
  });
});
