import { act, fireEvent, render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { describe, expect, it, vi } from 'vitest';
import type { PuzzleRepository, PuzzleSolver } from '@/application';
import type { Puzzle } from '@/domain';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as IndexRoute } from '@/ui/routes/grille';

// Integration regression for the hint feature. Renders the real Index
// route (Grid + HintControl wired through the route's `getFocusedCell`
// seam) and exercises the realistic blur→click sequence a real browser
// produces when the user has a letter cell focused and clicks the hint
// button.
//
// Why this test exists: the unit tests (`hint-control.test.tsx`,
// `use-hint-request.test.ts`) stub `getFocusedCell` directly, so they
// never exercise the route's `activeFocusRef` plumbing. In production
// the cell `<input onBlur>` sets `focused = null`, the focus-change
// `useEffect` flushes between the blur and the click (React 18's
// between-discrete-event passive-effect flush), and `getFocusedCell`
// would return `null` — the hint silently no-ops. This test fails on
// the pre-fix wiring and passes once `handleLocalFocusChange` keeps
// the last non-null focus.

// 1×3 row: definition at (0,0) + letter cells at (0,1) and (0,2). The
// across word starts at (0,1) so clicking (0,1) sets direction=across
// without us having to disambiguate stacked clues.
const puzzle: Puzzle = {
  id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  title: 'hint-test',
  language: 'fr',
  width: 3,
  height: 1,
  hintsAllowed: 3,
  cells: [
    {
      kind: 'definition',
      position: { row: 0, col: 0 },
      clues: [{ text: 'a', arrow: 'right' }],
    },
    { kind: 'letter', position: { row: 0, col: 1 }, entry: '' },
    { kind: 'letter', position: { row: 0, col: 2 }, entry: '' },
  ],
};

const inputAt = (row: number, col: number) =>
  document.querySelector<HTMLInputElement>(
    `input[data-cell-kind="letter"][data-row="${row}"][data-col="${col}"]`,
  );

const renderHomeRoute = (solver: PuzzleSolver) => {
  const repository: PuzzleRepository = {
    fetchById: vi.fn().mockResolvedValue(puzzle),
  };
  const routeTree = RootRoute.addChildren([IndexRoute]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ['/grille'] }),
    context: {
      puzzleRepository: repository,
      puzzleSolver: solver,
      sessionClient: {
        eraseSession: () => Promise.resolve({ deleted: 0 }),
        getSessionId: () => 'test-session-id',
        clearLocalSession: () => {},
      },
      soloEntriesStore: {
        load: () => [],
        save: () => {},
        loadLockedCells: () => [],
        lockCell: () => {},
        clearForPuzzle: () => {},
      },
      tourSeenStore: {
        get: () => true,
        set: () => {},
        clear: () => {},
      },
    },
  });
  return render(<RouterProvider router={router} />);
};

const click = (el: HTMLElement) => {
  el.focus();
  fireEvent.click(el);
};
const typeChar = (el: HTMLInputElement, ch: string) =>
  fireEvent.keyDown(el, { key: ch });

describe('Index route — hint button integration', () => {
  it('sends the focused cell coordinates to the solver after a real blur→click sequence', async () => {
    const requestHint = vi.fn().mockResolvedValue({
      row: 0,
      column: 2,
      letter: 'B',
      hintsRemaining: 2,
    });
    const solver: PuzzleSolver = {
      validate: vi.fn().mockResolvedValue({ solved: false, incorrectCells: [] }),
      requestHint,
    };

    renderHomeRoute(solver);
    await screen.findByRole('grid');

    // Focus the first letter cell (= start of the across word) and type
    // a letter; the cell handler advances focus to (0, 2). That cell is
    // the one the hint should target after the blur→click race below.
    const first = inputAt(0, 1)!;
    click(first);
    typeChar(first, 'a');

    // Real browsers fire `blur` on the focused cell when the user
    // mousedowns the toolbar button, and React 18 flushes pending
    // passive effects between the blur and the click — so the
    // focus-change `useEffect` would write `null` into `activeFocusRef`
    // before `onClick` reads it. jsdom doesn't move focus on
    // `fireEvent.click`, so we simulate the sequence explicitly: blur
    // the cell inside `act()` (forcing the same passive-effect flush
    // the real browser does between events), then click the button.
    const second = inputAt(0, 2)!;
    act(() => {
      second.blur();
    });

    const hintButton = screen.getByRole('button', { name: 'Demander un indice' });
    await act(async () => {
      fireEvent.click(hintButton);
    });

    expect(requestHint).toHaveBeenCalledTimes(1);
    expect(requestHint).toHaveBeenCalledWith(puzzle.id, 0, 2);
    // The success path writes the new hintsRemaining into the badge
    // and writes the revealed letter into the matching <input>.
    await screen.findByLabelText('2 sur 3 indices restants');
    expect(inputAt(0, 2)!.value).toBe('B');
  });
});
