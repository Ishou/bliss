import { act, fireEvent, render, screen } from '@testing-library/react';
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { LobbySummary } from '@/application/game';
import type { LobbyId } from '@/domain/game';
import { MyLobbiesSection } from '@/ui/components/lobby/MyLobbiesSection';

const lobby: LobbySummary = {
  id: 'AAAA1111BBBB2222CCCC3333' as LobbyId,
  code: 'A2B3C4',
  state: 'WAITING',
  gridConfig: { width: 7, height: 7 },
  playerCount: 3,
  lastActivityAt: '2026-05-12T10:00:00Z',
  progress: { solvedCells: 12, totalCells: 50 },
};

function renderAt(node: React.ReactNode) {
  const rootRoute = createRootRoute();
  const indexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: () => <>{node}</>,
  });
  const lobbyRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/lobby/$lobbyId',
    component: () => <div>lobby</div>,
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([indexRoute, lobbyRoute]),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  });
  return render(<RouterProvider router={router} />);
}

describe('<MyLobbiesSection> — streamer-safe code surface', () => {
  it('masks the join code by default', async () => {
    renderAt(<MyLobbiesSection lobbies={[lobby]} />);
    const codeEl = await screen.findByTestId('lobby-code');
    expect(codeEl.getAttribute('data-masked')).toBe('true');
    expect(codeEl.textContent).not.toBe(lobby.code);
    expect(codeEl.textContent).toContain('•'); // bullet glyph
    expect(screen.getByRole('button', { name: /afficher le code/i })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
  });

  it('reveals the code when the eye toggle is clicked, and re-masks on a second click', async () => {
    renderAt(<MyLobbiesSection lobbies={[lobby]} />);
    await screen.findByTestId('lobby-code');
    const showToggle = screen.getByRole('button', { name: /afficher le code/i });
    act(() => { fireEvent.click(showToggle); });
    expect(screen.getByTestId('lobby-code').textContent).toBe(lobby.code);
    expect(screen.getByTestId('lobby-code').getAttribute('data-masked')).toBe('false');
    const hideToggle = screen.getByRole('button', { name: /masquer le code/i });
    expect(hideToggle).toHaveAttribute('aria-pressed', 'true');
    act(() => { fireEvent.click(hideToggle); });
    expect(screen.getByTestId('lobby-code').getAttribute('data-masked')).toBe('true');
  });
});

describe('<MyLobbiesSection> — copy button', () => {
  const writeText = vi.fn().mockResolvedValue(undefined);

  beforeEach(() => {
    writeText.mockClear();
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    });
  });

  afterEach(() => {
    // Leave navigator.clipboard defined for other suites; no-op.
  });

  it('writes the lobby code to the clipboard on click', async () => {
    renderAt(<MyLobbiesSection lobbies={[lobby]} />);
    const copy = await screen.findByRole('button', { name: /copier le code/i });
    act(() => { fireEvent.click(copy); });
    expect(writeText).toHaveBeenCalledWith(lobby.code);
    expect(await screen.findByRole('status')).toHaveTextContent(/copi/i);
  });
});

describe('<MyLobbiesSection> — player count', () => {
  it('renders "X / 8 joueurs" using the canonical 8-player cap', async () => {
    renderAt(<MyLobbiesSection lobbies={[lobby]} />);
    const players = await screen.findByTestId('lobby-players');
    expect(players.textContent).toContain('3 / 8');
    expect(players.textContent).toMatch(/joueurs/);
  });
});

describe('<MyLobbiesSection> — progress bar', () => {
  it('renders a progress bar with the solved/total cells ratio from the summary', async () => {
    renderAt(<MyLobbiesSection lobbies={[lobby]} />);
    const bar = await screen.findByRole('progressbar');
    expect(bar.getAttribute('aria-valuenow')).toBe('12');
    expect(bar.getAttribute('aria-valuemax')).toBe('50');
    const text = screen.getByTestId('puzzle-progress').textContent ?? '';
    expect(text).toContain('12 / 50');
    // Regression guard: NBSP+colon separator prevents "Progression0 / 0 cases" collapse.
    expect(text).toMatch(/Progression\u00a0:\s*12 \/ 50 cases/);
  });
});

describe('<MyLobbiesSection> — empty state', () => {
  it('renders the empty-state blurb when no lobbies are present', async () => {
    renderAt(<MyLobbiesSection lobbies={[]} />);
    expect(
      await screen.findByText(/vos parties multijoueur en cours apparaîtront ici/i),
    ).toBeInTheDocument();
  });
});
